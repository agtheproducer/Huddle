// Calculator frontend for the Agent Board demo.
// Talks to julieta-claude's Kotlin backend:
//   GET /api/calc?a=12&op=add&b=3 -> 200 {"result":15.0}
//   bad input / division by zero  -> 400 {"error":"..."}

const form = document.getElementById('form');
const display = document.getElementById('display');
const statusEl = document.getElementById('status');
const go = document.getElementById('go');
const base = document.getElementById('base');

const SYMBOL = { add: '+', sub: '−', mul: '×', div: '÷', pow: '^' };

// pow arrived in the backend after index.html was written, so add its option here
// instead of editing a file owned by another agent.
const opSelect = document.getElementById('op');
if (!opSelect.querySelector('option[value="pow"]')) {
  const powOption = document.createElement('option');
  powOption.value = 'pow';
  powOption.textContent = '^  pow';
  opSelect.append(powOption);
}

function show(text, isError) {
  display.textContent = text;
  display.classList.toggle('error', Boolean(isError));
}

function setStatus(text, isError) {
  statusEl.textContent = text;
  statusEl.classList.toggle('error', Boolean(isError));
}

async function calculate() {
  const a = document.getElementById('a').value.trim();
  const b = document.getElementById('b').value.trim();
  const op = document.getElementById('op').value;

  if (a === '' || b === '') {
    show('needs two numbers', true);
    setStatus('', false);
    return;
  }

  const url = `${base.value.replace(/\/+$/, '')}/api/calc`
    + `?a=${encodeURIComponent(a)}&op=${encodeURIComponent(op)}&b=${encodeURIComponent(b)}`;

  go.disabled = true;
  setStatus(`${a} ${SYMBOL[op]} ${b} …`, false);

  try {
    const res = await fetch(url);
    const body = await res.json().catch(() => null);

    if (!res.ok) {
      show((body && body.error) || `HTTP ${res.status}`, true);
      setStatus(`${a} ${SYMBOL[op]} ${b} → ${res.status}`, true);
      return;
    }

    show(format(body.result), false);
    setStatus(`${a} ${SYMBOL[op]} ${b}`, false);
  } catch (err) {
    show('backend unreachable', true);
    setStatus(`cannot reach ${base.value} — start it with ./gradlew :calc-backend:run`, true);
  } finally {
    go.disabled = false;
  }
}

function format(n) {
  if (typeof n !== 'number' || !Number.isFinite(n)) return String(n);
  return Number.isInteger(n) ? String(n) : String(Math.round(n * 1e10) / 1e10);
}

form.addEventListener('submit', (e) => {
  e.preventDefault();
  calculate();
});

// Enter in a number field submits; recalc when the operator changes.
opSelect.addEventListener('change', calculate);

calculate();
