import asyncio
from walter_modem import Modem
from walter_modem.mixins.socket import SocketMixin
from walter_modem.mixins.tls_certs import TLSCertsMixin
from walter_modem.coreStructs import WalterModemRsp
from walter_modem.mixins.default_sim_network import *
from walter_modem.mixins.default_pdp import *
from walter_modem.coreEnums import WalterModemNetworkRegState, WalterModemOpState

from helpers import load_config, save_wav
from audio_apis import Gemini

modem = Modem(SocketMixin, TLSCertsMixin, load_default_power_saving_mixin=False)
modem_rsp = WalterModemRsp()

async def wait_for_network(timeout=180):
    for _ in range(timeout):
        state = modem.get_network_reg_state()
        if state in (WalterModemNetworkRegState.REGISTERED_HOME,
                     WalterModemNetworkRegState.REGISTERED_ROAMING):
            return True
        await asyncio.sleep(1)
    return False

async def lte_connect():
    if modem.get_network_reg_state() in (
            WalterModemNetworkRegState.REGISTERED_HOME,
            WalterModemNetworkRegState.REGISTERED_ROAMING):
        if await modem.get_rat(rsp=modem_rsp):
            if modem_rsp.rat == WalterModemRat.LTEM:
                print("Already connected to LTE-M")
                return True

    print("Setting RAT to LTE-M...")
    if not await modem.set_rat(WalterModemRat.LTEM):
        return False
    if not await modem.set_op_state(WalterModemOpState.FULL):
        return False
    if not await modem.set_network_selection_mode(WalterModemNetworkSelMode.AUTOMATIC):
        return False

    print("Waiting for LTE-M network...")
    if not await wait_for_network(180):
        return False

    if await modem.get_rat(rsp=modem_rsp):
        print(f"Connected on: {WalterModemRat.get_value_name(modem_rsp.rat)}")
    return True

async def main():
    config = load_config(api_key="ryder")

    await modem.begin(uart_debug=False)

    if not await modem.check_comm():
        raise RuntimeError("Modem failed")

    if config['sim_pin'] and not await modem.unlock_sim(pin=config['sim_pin']):
        raise RuntimeError("SIM unlock failed")

    if not await modem.create_PDP_context(apn=config['cell_apn']):
        raise RuntimeError("PDP context failed")

    if config['apn_username']:
        await modem.set_PDP_auth_params(
            protocol=WalterModemPDPAuthProtocol.PAP,
            user_id=config['apn_username'],
            password=config['apn_password']
        )

    if not await lte_connect():
        raise RuntimeError("LTE-M connection failed")

    gemini = Gemini(modem, modem_rsp, api_key=config['gemini_api_key'], verbosity=2)

    # text, latency = await gemini.a2t(config['audio_file'], config['audio_prompt'])
    # print(f"Transcription: {text} ({latency}ms)")
    text = "Who was the first president of the United States of America?"
    wav_bytes, latency = await gemini.t2a(f"Say this fast:\n{text}")
    print("Audio received\n"
        f"    Data:                {len(wav_bytes)}B\n"
        f"    Latency:             {latency}ms\n"
    )
    if wav_bytes:
        await save_wav(wav_bytes, "/tts_output.wav")

asyncio.run(main())