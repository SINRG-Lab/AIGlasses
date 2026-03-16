#include <Arduino.h>

void setup() {
    Serial.begin(115200);
    delay(3000);
    Serial.println("Booted!");
    Serial.flush();
}

void loop() {
    Serial.println("Test of basic operation");
    Serial.flush();
    delay(5000);
}