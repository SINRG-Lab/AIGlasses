#!/bin/bash
# Quick fix script for IntelliSense issues
# Run this if you see "no such file or directory" errors in VS Code

echo "🔧 Fixing IntelliSense issues..."
echo ""

echo "Step 1: Cleaning project..."
pio run -t clean

echo ""
echo "Step 2: Building project (this downloads frameworks)..."
pio run

echo ""
echo "Step 3: Generating compile database for IntelliSense..."
pio run -t compiledb

echo ""
echo "✅ Done! Now:"
echo "   1. Wait 10-30 seconds for IntelliSense to index"
echo "   2. Restart VS Code if errors persist"
echo "   3. Check that 'pio run' completed successfully"
echo ""
echo "If errors still appear but 'pio run' works, it's just IntelliSense being slow."
echo "Your code is correct - the compiler is what matters!"
