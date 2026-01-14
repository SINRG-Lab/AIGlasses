# Fixing IntelliSense "No Such File or Directory" Errors

If you're seeing red squiggly lines under `#include <Arduino.h>` or other core libraries, follow these steps in order:

## Quick Fix (Try This First)

1. **Build the project** - This downloads and indexes all libraries:
   ```bash
   pio run
   ```
   Or click the ✓ (checkmark) icon in the PlatformIO toolbar in VS Code.

2. **Wait for indexing** - After the build completes, wait 10-30 seconds for IntelliSense to index the files.

3. **Restart VS Code** - Close and reopen VS Code to refresh IntelliSense.

## If Quick Fix Doesn't Work

### Step 1: Clean and Rebuild
```bash
pio run -t clean
pio run
```

### Step 2: Generate Compile Database (for better IntelliSense)
```bash
pio run -t compiledb
```
This creates a `compile_commands.json` file that helps IntelliSense understand your project structure.

### Step 3: Verify Platform Installation
```bash
pio platform show espressif32
```
If it shows "Not installed" or errors, install/update:
```bash
pio platform install espressif32
pio platform update espressif32
```

### Step 4: Reinstall Framework (if corrupted)
Close VS Code, then:
```bash
# On macOS/Linux:
rm -rf ~/.platformio/packages/framework-arduinoespressif32

# On Windows:
# Delete: %USERPROFILE%\.platformio\packages\framework-arduinoespressif32
```
Then restart VS Code and build again - PlatformIO will re-download the framework.

### Step 5: Reset IntelliSense Cache
1. Close VS Code
2. Delete the `.vscode/.browse.vc.db` file (if it exists)
3. Restart VS Code
4. Build the project

## Verify Configuration

Your `platformio.ini` should have:
```ini
[env:esp32dev]
platform = espressif32
board = esp32dev
framework = arduino
```

**Correct**: `framework = arduino`  
**Wrong**: `framework = espidf` or missing framework line

## Common Issues

### Issue: "Arduino.h: No such file or directory"
**Solution**: Build the project first. IntelliSense needs the framework to be downloaded.

### Issue: Red squiggles but code compiles fine
**Solution**: This is normal! IntelliSense is just out of sync. Build the project and wait, or restart VS Code.

### Issue: Errors persist after building
**Solution**: 
1. Run `pio run -t compiledb`
2. Restart VS Code
3. If still broken, reinstall the platform: `pio platform install --reinstall espressif32`

### Issue: BLE headers not found
**Solution**: ESP32 BLE is included with the framework. Ensure `framework = arduino` is set, then build.

## Manual IntelliSense Configuration

If automatic configuration doesn't work, the project includes `.vscode/settings.json` with manual paths. However, PlatformIO should handle this automatically.

## Still Having Issues?

1. Check PlatformIO version: `pio --version` (should be 5.0+)
2. Update PlatformIO: `pio upgrade`
3. Check VS Code C/C++ extension is installed and enabled
4. Try the PlatformIO IDE extension's "Rebuild IntelliSense Index" command

## Note

**Red squiggles don't always mean errors!** If `pio run` compiles successfully, your code is correct. IntelliSense is just a helper - the actual compiler is what matters.
