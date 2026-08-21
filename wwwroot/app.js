const $ = id => document.getElementById(id);
const fmt = p => '£' + (p / 100).toFixed(2);

let state = null;           // people, items, totals, lastOrderId
let basket = null;          // { personId, personName, balanceP, lines:{itemId:units}, key }
let armed = false;          // delete armed on the open edit panel
let editing = null;         // { kind:'person'|'item', row }
let undoDiscarded = false;  // a deletion invalidates the pending undo

// ---------------------------------------------------------------- transport

async function api(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/x-www-form-urlencoded' } : undefined,
    body: body ? new URLSearchParams(body).toString() : undefined
  });
  if (res.status === 401) { $('app').hidden = true; $('login').hidden = false; throw new Error('Signed out.'); }
  const data = res.status === 204 ? {} : await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || 'That did not go through. Try again.');
  return data;
}

function say(msg) {
  const b = $('banner');
  b.textContent = msg || '';
  b.hidden = !msg;
}

async function run(fn) {
  try { say(''); await fn(); }
  catch (e) { say(e.message); }
}

// ---------------------------------------------------------------- load

async function load() {
  state = await api('GET', '/api/state');
  renderSell(); renderPeople(); renderStock(); renderOwed(); renderProfit();
}

// ---------------------------------------------------------------- sell

function basketUnits(itemId) { return (basket && basket.lines[itemId]) || 0; }
function basketTotal() {
  if (!basket) return 0;
  return Object.entries(basket.lines)
    .reduce((t, [id, u]) => t + u * state.items.find(i => i.id === +id).priceP, 0);
}

function renderSell() {
  const picking = !basket;
  $('sellPick').hidden = !picking;
  $('sellBasket').hidden = picking;

  if (picking) {
    $('sellPeople').innerHTML = '';
    for (const p of state.people) {
      const b = document.createElement('button');
      b.className = 'row';
      b.innerHTML = `<span>${esc(p.name)}</span><span class="money">${fmt(p.balanceP)}</span>`;
      b.onclick = () => {
        basket = { personId: p.id, personName: p.name, balanceP: p.balanceP, lines: {}, key: newKey() };
        renderSell();
      };
      $('sellPeople').appendChild(b);
    }
    $('undoBtn').disabled = !state.lastOrderId || undoDiscarded;
    return;
  }

  const total = basketTotal();
  const left = basket.balanceP - total;
  $('sellName').textContent = basket.personName;
  $('sellLeft').textContent = fmt(left) + ' left after this';
  $('basketTotal').textContent = fmt(total);
  $('confirmBtn').disabled = total === 0;

  $('sellItems').innerHTML = '';
  for (const it of state.items) {
    const b = document.createElement('button');
    b.innerHTML = `<span>${esc(it.name)}</span>
                   <span class="money">${fmt(it.priceP)}</span>
                   <span class="sub">${it.qty - basketUnits(it.id)} left</span>`;
    b.disabled = !canAdd(it);
    b.onclick = () => { basket.lines[it.id] = basketUnits(it.id) + 1; renderSell(); };
    $('sellItems').appendChild(b);
  }

  $('basketLines').innerHTML = '';
  for (const [id, units] of Object.entries(basket.lines)) {
    const it = state.items.find(i => i.id === +id);
    const row = document.createElement('div');
    row.className = 'row';
    row.innerHTML = `<span>${units} &times; ${esc(it.name)}<span class="sub">${fmt(units * it.priceP)}</span></span>`;
    const qty = document.createElement('div');
    qty.className = 'qty';

    const minus = document.createElement('button');
    minus.textContent = '\u2212';
    minus.onclick = () => {
      if (units <= 1) delete basket.lines[id]; else basket.lines[id] = units - 1;
      renderSell();
    };

    const plus = document.createElement('button');
    plus.textContent = '+';
    plus.disabled = !canAdd(it);
    plus.onclick = () => { basket.lines[id] = units + 1; renderSell(); };

    qty.append(minus, plus);
    row.appendChild(qty);
    $('basketLines').appendChild(row);
  }
}

function canAdd(it) {
  return it.qty - basketUnits(it.id) > 0 &&
         basket.balanceP - basketTotal() >= it.priceP;
}

function newKey() {
  return (crypto.randomUUID && crypto.randomUUID()) ||
         (Date.now() + '-' + Math.random().toString(16).slice(2));
}

$('backBtn').onclick = () => { basket = null; renderSell(); };
$('clearBtn').onclick = () => { basket.lines = {}; renderSell(); };

$('confirmBtn').onclick = () => run(async () => {
  const lines = Object.entries(basket.lines).map(([itemId, units]) => `${itemId}:${units}`).join(',');
  await api('POST', '/api/orders', { personId: basket.personId, lines, idempotencyKey: basket.key });
  basket = null;
  undoDiscarded = false;
  await load();
});

$('undoBtn').onclick = () => run(async () => {
  await api('POST', `/api/orders/${state.lastOrderId}/undo`);
  await load();
});

// ---------------------------------------------------------------- people

function topUpMode() { return parseFloat($('pAmount').value) > 0; }

function renderPeople() {
  $('pMode').textContent = topUpMode()
    ? `Tapping a person adds ${fmt(Math.round(parseFloat($('pAmount').value) * 100))} to their balance.`
    : 'Tapping a person opens their details. Type an amount first to top up instead.';

  $('peopleList').innerHTML = '';
  for (const p of state.people) {
    const b = document.createElement('button');
    b.className = 'row';
    b.innerHTML = `<span>${esc(p.name)}</span><span class="money">${fmt(p.balanceP)}</span>`;
    b.onclick = () => run(async () => {
      if (topUpMode()) {
        await api('POST', `/api/people/${p.id}/topup`, { amount: $('pAmount').value });
        $('pAmount').value = '';
        await load();
      } else {
        openEdit('person', p);
      }
    });
    $('peopleList').appendChild(b);
  }
}

$('pAmount').oninput = () => renderPeople();

$('pAdd').onclick = () => run(async () => {
  await api('POST', '/api/people', { name: $('pName').value, amount: $('pAmount').value });
  $('pName').value = ''; $('pAmount').value = '';
  await load();
});

// ---------------------------------------------------------------- stock

function renderStock() {
  $('stockList').innerHTML = '';
  for (const it of state.items) {
    const b = document.createElement('button');
    b.className = 'row';
    b.innerHTML = `<span>${esc(it.name)}<span class="sub">${it.qty} left, ${it.sold} sold</span></span>
                   <span class="money">${fmt(it.priceP)}</span>`;
    b.onclick = () => openEdit('item', it);
    $('stockList').appendChild(b);
  }
}

$('sAdd').onclick = () => run(async () => {
  await api('POST', '/api/items', {
    name: $('sName').value, price: $('sPrice').value,
    qty: $('sQty').value, cost: $('sCost').value
  });
  ['sName', 'sPrice', 'sQty', 'sCost'].forEach(id => $(id).value = '');
  await load();
});

// ---------------------------------------------------------------- owed / profit

function renderOwed() {
  $('owedTotal').textContent = fmt(state.totals.owedP);
  $('owedList').innerHTML = state.people
    .map(p => `<div class="row"><span>${esc(p.name)}</span><span class="money">${fmt(p.balanceP)}</span></div>`)
    .join('');
}

function renderProfit() {
  const t = state.totals;
  $('fProfit').textContent = fmt(t.profitP);
  $('fRevenue').textContent = fmt(t.revenueP);
  $('fCost').textContent = fmt(t.costP);
  $('fShelf').textContent = fmt(t.shelfP);
  $('profitList').innerHTML = state.items.map(i =>
    `<div class="row"><span>${esc(i.name)}<span class="sub">${i.sold} sold at ${fmt(i.costEachP)} each</span></span>
     <span class="money">${fmt(i.marginP)}</span></div>`).join('');
}

// ---------------------------------------------------------------- log

async function renderLog() {
  const { lines } = await api('GET', '/api/log');
  $('logBody').textContent = lines.join('\n') || 'Nothing logged yet.';
}

$('logPass').oninput = () => { $('logClear').disabled = !$('logPass').value; };

$('logClear').onclick = () => run(async () => {
  await api('POST', '/api/log/clear', { password: $('logPass').value });
  $('logPass').value = '';
  $('logClear').disabled = true;
  await renderLog();
});

// ---------------------------------------------------------------- edit panel

function openEdit(kind, row) {
  editing = { kind, row };
  armed = false;
  $('editDelete').textContent = 'Delete';
  $('editTitle').textContent = kind === 'person' ? row.name : row.name;

  const f = kind === 'person'
    ? [['Name', 'eName', row.name], ['Balance', 'eBalance', (row.balanceP / 100).toFixed(2)]]
    : [['Name', 'eName', row.name],
       ['Selling price each', 'ePrice', (row.priceP / 100).toFixed(2)],
       ['How many left', 'eQty', row.qty],
       ['Total spent buying this', 'eSpent', (row.spentP / 100).toFixed(2)]];

  $('editFields').innerHTML = f
    .map(([label, id, val]) => `<label for="${id}">${label}</label><input id="${id}" value="${esc(String(val))}">`)
    .join('');
  $('editPanel').hidden = false;
}

function closeEdit() { $('editPanel').hidden = true; editing = null; armed = false; }
$('editCancel').onclick = closeEdit;

$('editSave').onclick = () => run(async () => {
  const { kind, row } = editing;
  if (kind === 'person')
    await api('PUT', `/api/people/${row.id}`,
      { name: $('eName').value, balance: $('eBalance').value, version: row.version });
  else
    await api('PUT', `/api/items/${row.id}`,
      { name: $('eName').value, price: $('ePrice').value, qty: $('eQty').value,
        spent: $('eSpent').value, version: row.version });
  closeEdit();
  await load();
});

$('editDelete').onclick = () => run(async () => {
  if (!armed) { armed = true; $('editDelete').textContent = 'Tap again to delete'; return; }
  const { kind, row } = editing;
  await api('DELETE', kind === 'person' ? `/api/people/${row.id}` : `/api/items/${row.id}`);
  basket = null;            // a deletion invalidates the basket
  undoDiscarded = true;     // ...and the pending undo
  closeEdit();
  await load();
});

// ---------------------------------------------------------------- tabs

for (const b of document.querySelectorAll('#tabs button')) {
  b.onclick = () => run(async () => {
    // Switching tabs resets every pending confirmation.
    closeEdit();
    say('');
    document.querySelectorAll('#tabs button').forEach(x => x.classList.toggle('on', x === b));
    document.querySelectorAll('.tab').forEach(t => t.hidden = t.id !== 'tab-' + b.dataset.tab);
    if (b.dataset.tab === 'log') await renderLog(); else await load();
  });
}

// ---------------------------------------------------------------- login

$('loginBtn').onclick = () => run(async () => {
  try {
    await api('POST', '/api/login', { username: $('loginUser').value, password: $('loginPass').value });
  } catch (e) { $('loginErr').textContent = e.message; return; }
  $('loginErr').textContent = '';
  $('loginPass').value = '';
  $('login').hidden = true;
  $('app').hidden = false;
  await load();
});

$('loginPass').onkeydown = e => { if (e.key === 'Enter') $('loginBtn').click(); };

function esc(s) {
  return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

// Already signed in?
api('GET', '/api/state')
  .then(s => { state = s; $('login').hidden = true; $('app').hidden = false; return load(); })
  .catch(() => {});
