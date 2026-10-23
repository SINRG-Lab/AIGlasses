/*
 * TK43-TP223 TOUCH SENSOR x6 - ESP32 S3
 *
 * Physical layout:
 *   [4 - GPIO3]  [5 - GPIO4]  [6 - GPIO5]   <- top row
 *   [1 - GPIO9]  [2 - GPIO1]  [3 - GPIO2]   <- bottom row
 *
 * Sequences:
 *   1->2->3  Swiped LEFT  (Top)
 *   3->2->1  Swiped RIGHT (Top)
 *   4->5->6  Swiped LEFT  (Bottom)
 *   6->5->4  Swiped RIGHT (Bottom)
 *   1->4     Swiped UP    (Left Side)
 *   2->5     Swiped UP    (Center)
 *   3->6     Swiped UP    (Right Side)
 *   4->1     Swiped DOWN  (Left Side)
 *   5->2     Swiped DOWN  (Center)
 *   6->3     Swiped DOWN  (Right Side)
 *
 * Open Serial Monitor at 115200 baud.
 */

#define TOUCH1_PIN 9
#define TOUCH2_PIN 1
#define TOUCH3_PIN 2
#define TOUCH4_PIN 3
#define TOUCH5_PIN 4
#define TOUCH6_PIN 5
#define LED_PIN    48

#define SEQUENCE_TIMEOUT 2000  // ms — reset if gap between touches exceeds this

int  sequence[3];
int  seqIndex       = 0;
unsigned long lastTouchTime = 0;

bool prev1 = LOW, prev2 = LOW, prev3 = LOW;
bool prev4 = LOW, prev5 = LOW, prev6 = LOW;

void checkSequence() {

  // --- 2-touch sequences ---
  if (seqIndex == 2) {
    // DOWN swipes (bottom -> top)
    if      (sequence[0] == 1 && sequence[1] == 4) Serial.println(">>> Swiped DOWN (Left Side) <<<");
    else if (sequence[0] == 2 && sequence[1] == 5) Serial.println(">>> Swiped DOWN (Center) <<<");
    else if (sequence[0] == 3 && sequence[1] == 6) Serial.println(">>> Swiped DOWN (Right Side) <<<");
    // UP swipes (top -> bottom)
    else if (sequence[0] == 4 && sequence[1] == 1) Serial.println(">>> Swiped UP (Left Side) <<<");
    else if (sequence[0] == 5 && sequence[1] == 2) Serial.println(">>> Swiped UP (Center) <<<");
    else if (sequence[0] == 6 && sequence[1] == 3) Serial.println(">>> Swiped UP (Right Side) <<<");
    else return;  // No match — keep building towards a 3-touch sequence
    seqIndex = 0;
    return;
  }

  // --- 3-touch sequences ---
  if (seqIndex == 3) {
    // Top row horizontal
    if      (sequence[0] == 1 && sequence[1] == 2 && sequence[2] == 3) Serial.println(">>> Swiped LEFT (Top) <<<");
    else if (sequence[0] == 3 && sequence[1] == 2 && sequence[2] == 1) Serial.println(">>> Swiped RIGHT (Top) <<<");
    // Bottom row horizontal
    else if (sequence[0] == 4 && sequence[1] == 5 && sequence[2] == 6) Serial.println(">>> Swiped LEFT (Bottom) <<<");
    else if (sequence[0] == 6 && sequence[1] == 5 && sequence[2] == 4) Serial.println(">>> Swiped RIGHT (Bottom) <<<");
    else Serial.println("Sequence not recognised — resetting");
    seqIndex = 0;
  }
}

void recordTouch(int sensor) {
  Serial.print("Touch ");
  Serial.print(sensor);
  Serial.println(" is touched");

  lastTouchTime = millis();
  sequence[seqIndex++] = sensor;

  if (seqIndex >= 2) checkSequence();
}

void setup() {
  pinMode(TOUCH1_PIN, INPUT_PULLDOWN);
  pinMode(TOUCH2_PIN, INPUT_PULLDOWN);
  pinMode(TOUCH3_PIN, INPUT_PULLDOWN);
  pinMode(TOUCH4_PIN, INPUT_PULLDOWN);
  pinMode(TOUCH5_PIN, INPUT_PULLDOWN);
  pinMode(TOUCH6_PIN, INPUT_PULLDOWN);
  pinMode(LED_PIN, OUTPUT);

  Serial.begin(115200);
  delay(1000);
  Serial.println("Swipe detector ready");
  Serial.println("[4][5][6] <- top | [1][2][3] <- bottom");
}

void loop() {
  bool cur1 = digitalRead(TOUCH1_PIN);
  bool cur2 = digitalRead(TOUCH2_PIN);
  bool cur3 = digitalRead(TOUCH3_PIN);
  bool cur4 = digitalRead(TOUCH4_PIN);
  bool cur5 = digitalRead(TOUCH5_PIN);
  bool cur6 = digitalRead(TOUCH6_PIN);

  // Reset sequence if gap between touches is too long
  if (seqIndex > 0 && millis() - lastTouchTime > SEQUENCE_TIMEOUT) {
    Serial.println("Timed out — sequence reset");
    seqIndex = 0;
  }

  // Rising-edge detection — fires once per tap, not while held
  if (cur1 == HIGH && prev1 == LOW) recordTouch(1);
  if (cur2 == HIGH && prev2 == LOW) recordTouch(2);
  if (cur3 == HIGH && prev3 == LOW) recordTouch(3);
  if (cur4 == HIGH && prev4 == LOW) recordTouch(4);
  if (cur5 == HIGH && prev5 == LOW) recordTouch(5);
  if (cur6 == HIGH && prev6 == LOW) recordTouch(6);

  prev1 = cur1;  prev2 = cur2;  prev3 = cur3;
  prev4 = cur4;  prev5 = cur5;  prev6 = cur6;

  delay(50);
}
