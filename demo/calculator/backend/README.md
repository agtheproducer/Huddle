# Calculator backend (Kotlin)

Small HTTP backend for the Agent Board demo calculator.

```bash
./gradlew :calc-backend:run        # http://localhost:8090 (or CALC_PORT)
./gradlew :calc-backend:test
```

## API

`GET /api/calc?a=12&op=add&b=3` → `200 {"result":15.0}`

| `op` | Meaning |
| --- | --- |
| `add` | a + b |
| `sub` | a − b |
| `mul` | a × b |
| `div` | a ÷ b (division by zero → 400) |

Bad input returns `400 {"error":"..."}`. CORS is open, so the frontend in
`demo/calculator/frontend` can call it from any page.

## Adding an operation

1. Add it to `Op` in `Calculator.kt` with its URL name.
2. Add its branch to `Calculator.calculate`.
3. Add a case to `CalculatorTest.kt`.
