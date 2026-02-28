import asyncio

import micropython

from walter_modem import Modem
from walter_modem.mixins.socket import SocketMixin
from walter_modem.mixins.tls_certs import TLSCertsMixin
from walter_modem.coreStructs import WalterModemRsp
from walter_modem.mixins.default_sim_network import *
from walter_modem.mixins.default_pdp import *
from walter_modem.coreEnums import WalterModemNetworkRegState, WalterModemOpState

from helpers import load_config, save_wav
from audio_apis import Gemini, Deepgram

micropython.opt_level(1)

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

    deepgram = Deepgram(
        modem=modem,
        modem_rsp=modem_rsp,
        api_key=config["deepgram_api_key"],
        verbosity=1,
        socket_id=3,
    )

    wav_bytes, latency = await deepgram.a2a(
        audio_file="msg_tiny.wav",
        prompt="You are a helpful assistant. Be concise.",
        voice="aura-2-thalia-en",
        think_provider="open_ai",
        think_model="gpt-4o-mini",
        input_sample_rate=16000,
        output_sample_rate=8000,
        output_encoding="mulaw"
    )
    if wav_bytes:
        print("Audio received\n"
              f"    Data:                {len(wav_bytes)}B\n"
              f"    Latency:             {latency}ms\n"
              )
        await save_wav(wav_bytes, "/tts_output.wav")
    else:
        print(f"No audio received (latency: {latency}ms)")

asyncio.run(main())