'use strict';

// In memory only. sessionStorage would survive a refresh but is readable by any
// script on the page, and a demo does not need the convenience.
let token = null;
let role = null;
let poller = null;
let ruleNames = new Map();

const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
    const response = await fetch(path, {
        ...options,
        headers: { 'Authorization': `Bearer ${token}`, ...(options.headers || {}) }
    });

    if (response.status === 401) {
        signOut();
        throw new Error('Session expired');
    }
    if (!response.ok) {
        throw new Error(`${response.status} ${response.statusText}`);
    }
    return response.json();
}

async function signIn(event) {
    event.preventDefault();

    const button = event.target.querySelector('button');
    const error = $('auth-error');

    button.disabled = true;
    button.textContent = 'Signing in…';
    error.classList.add('hidden');

    try {
        const response = await fetch('/v1/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: $('email').value, password: $('password').value })
        });

        // The API returns the same message for an unknown account as for a wrong
        // password, so there is nothing more specific to show.
        if (!response.ok) throw new Error('Invalid credentials');

        const session = await response.json();
        token = session.token;
        role = session.role;

        enterApp($('email').value);
    } catch (e) {
        error.textContent = e.message;
        error.classList.remove('hidden');
    } finally {
        button.disabled = false;
        button.textContent = 'Sign in';
    }
}

function signOut() {
    stopPolling();
    token = null;
    role = null;
    $('app-view').classList.add('hidden');
    $('session').classList.add('hidden');
    $('login-view').classList.remove('hidden');
}

// Detection is asynchronous, so a table read straight after sending events would
// show them before any rule had run. Polling is what makes the delay visible
// rather than looking like nothing happened.
function startPolling() {
    stopPolling();
    poller = setInterval(() => refresh().catch(() => {}), 2000);
}

function stopPolling() {
    if (poller) clearInterval(poller);
    poller = null;
}

async function simulate(kind, button) {
    const buttons = document.querySelectorAll('[data-sim]');
    buttons.forEach(b => b.disabled = true);
    const original = button.textContent;
    button.textContent = 'Sending…';

    try {
        const result = await api(`/v1/demo/simulate/${kind}`, { method: 'POST' });
        const banner = $('sim-result');
        banner.innerHTML =
            `<strong>${escape(result.summary)}</strong> — ${escape(result.eventsSent)} events sent. ` +
            `<span class="muted">${escape(result.hint)}</span>`;
        banner.classList.remove('hidden');
        await refresh();
    } catch (e) {
        const banner = $('sim-result');
        banner.textContent = `Failed: ${e.message}`;
        banner.classList.remove('hidden');
    } finally {
        buttons.forEach(b => b.disabled = false);
        button.textContent = original;
    }
}

async function refresh() {
    const [alerts, events, rules] = await Promise.all([
        api('/v1/management/alerts?size=50'),
        api('/v1/management/events?size=50'),
        api('/v1/management/rules')
    ]);

    ruleNames = new Map(rules.map(r => [r.id, r.name]));

    renderAlerts(alerts.content);
    renderEvents(events.content);
    renderRules(rules);

    $('stat-events').textContent = events.totalElements;
    $('stat-open').textContent = alerts.content.filter(a => a.status === 'OPEN').length;
    $('stat-rules').textContent = rules.length;
}

function renderAlerts(alerts) {
    const body = $('alerts-body');
    body.innerHTML = '';

    if (!alerts.length) {
        body.innerHTML = row(8, 'No alerts. Generate some traffic above.');
        return;
    }

    for (const alert of alerts) {
        // The dedupe key is ruleId:actor. Showing the raw uuid is noise; the
        // rule name and the account under attack are what an analyst reads.
        const actor = alert.dedupeKey.split(':').slice(1).join(':') || '—';
        const ruleName = ruleNames.get(alert.ruleId) ?? alert.ruleId.slice(0, 8);
        const canAct = role === 'ADMIN' || role === 'ANALYST';

        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><span class="sev ${alert.severity}">${alert.severity}</span></td>
            <td><span class="status ${alert.status}">${alert.status}</span></td>
            <td>${escape(ruleName)}</td>
            <td><code>${escape(actor)}</code></td>
            <td class="num"><span class="count">${alert.occurrenceCount}</span></td>
            <td class="muted">${ago(alert.firstSeenAt)}</td>
            <td class="muted">${ago(alert.lastSeenAt)}</td>
            <td>${canAct && alert.status !== 'RESOLVED'
                    ? `<button class="ghost" data-resolve="${alert.id}">Resolve</button>` : ''}</td>`;
        body.appendChild(tr);
    }

    body.querySelectorAll('[data-resolve]').forEach(button => {
        button.addEventListener('click', async () => {
            await api(`/v1/management/alerts/${button.dataset.resolve}/resolve`, { method: 'POST' });
            await refresh();
        });
    });
}

function renderEvents(events) {
    const body = $('events-body');
    body.innerHTML = events.length ? '' : row(6, 'No events yet.');

    for (const event of events) {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><span class="sev ${event.severity}">${event.severity}</span></td>
            <td>${escape(event.source)}</td>
            <td><code>${escape(event.eventType)}</code></td>
            <td>${escape(event.actor ?? '—')}</td>
            <td>${escape(event.target ?? '—')}</td>
            <td class="muted">${ago(event.occurredAt)}</td>`;
        body.appendChild(tr);
    }
}

function renderRules(rules) {
    const body = $('rules-body');
    body.innerHTML = rules.length ? '' : row(8, 'No rules configured.');

    for (const rule of rules) {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td>${escape(rule.name)}</td>
            <td>${escape(rule.matchSource ?? 'any')}</td>
            <td><code>${escape(rule.matchEventType ?? 'any')}</code></td>
            <td>${rule.minSeverity
                    ? `<span class="sev ${rule.minSeverity}">${rule.minSeverity}</span>` : 'any'}</td>
            <td class="num">${rule.thresholdCount}</td>
            <td class="num">${rule.windowSeconds}s</td>
            <td><span class="sev ${rule.alertSeverity}">${rule.alertSeverity}</span></td>
            <td>${role === 'ADMIN' ? `<button class="ghost" data-disable="${rule.id}">Remove</button>` : ''}</td>`;
        body.appendChild(tr);
    }

    body.querySelectorAll('[data-disable]').forEach(b => {
        b.addEventListener('click', async () => {
            await fetch(`/v1/management/rules/${b.dataset.disable}`, {
                method: 'DELETE', headers: { 'Authorization': `Bearer ${token}` }
            });
            await refresh();
        });
    });
}

const row = (cols, text) => `<tr><td colspan="${cols}" class="muted">${text}</td></tr>`;

function ago(iso) {
    const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
    if (seconds < 5) return 'just now';
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
    return new Date(iso).toLocaleDateString();
}

// Event actor and target are whatever an agent sent, so they are untrusted input
// heading for innerHTML. Neutralise before rendering.
function escape(value) {
    const div = document.createElement('div');
    div.textContent = String(value);
    return div.innerHTML;
}

async function signUp(event) {
    event.preventDefault();
    const button = event.target.querySelector('button');
    const error = $('auth-error');
    button.disabled = true; button.textContent = 'Creating…';
    error.classList.add('hidden');

    try {
        const response = await fetch('/v1/auth/signup', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                organisation: $('s-org').value,
                email: $('s-email').value,
                password: $('s-password').value
            })
        });
        const payload = await response.json();
        if (!response.ok) throw new Error(payload.detail || 'Could not create account');

        token = payload.token;
        role = payload.role;
        enterApp($('s-email').value);
    } catch (e) {
        error.textContent = e.message;
        error.classList.remove('hidden');
    } finally {
        button.disabled = false; button.textContent = 'Create account';
    }
}

async function issueKey(event) {
    event.preventDefault();
    const result = await api('/v1/management/api-keys', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: $('k-name').value })
    });
    const banner = $('new-key');
    banner.innerHTML = `<strong>Copy this now — it is not shown again.</strong><br>` +
        `<code class="key">${escape(result.apiKey)}</code>`;
    banner.classList.remove('hidden');
    $('k-name').value = '';
    await refreshKeys();
}

async function createRule(event) {
    event.preventDefault();
    await api('/v1/management/rules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            name: $('r-name').value,
            matchSource: $('r-source').value || null,
            matchEventType: $('r-type').value || null,
            minSeverity: $('r-min').value || null,
            thresholdCount: Number($('r-threshold').value),
            windowSeconds: Number($('r-window').value),
            alertSeverity: $('r-severity').value
        })
    });
    $('r-name').value = ''; $('r-source').value = ''; $('r-type').value = '';
    await refresh();
}

async function refreshKeys() {
    const keys = await api('/v1/management/api-keys');
    const body = $('keys-body');
    body.innerHTML = keys.length ? '' : row(5, 'No keys yet.');

    for (const key of keys) {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td>${escape(key.name)}</td>
            <td class="muted">${ago(key.createdAt)}</td>
            <td class="muted">${key.lastUsedAt ? ago(key.lastUsedAt) : 'never'}</td>
            <td>${key.revoked ? '<span class="muted">revoked</span>'
                              : '<span class="status OPEN">active</span>'}</td>
            <td>${key.revoked ? '' : `<button class="ghost" data-revoke="${key.id}">Revoke</button>`}</td>`;
        body.appendChild(tr);
    }

    body.querySelectorAll('[data-revoke]').forEach(b => {
        b.addEventListener('click', async () => {
            await fetch(`/v1/management/api-keys/${b.dataset.revoke}`, {
                method: 'DELETE', headers: { 'Authorization': `Bearer ${token}` }
            });
            await refreshKeys();
        });
    });
}

function enterApp(email) {
    $('login-view').classList.add('hidden');
    $('app-view').classList.remove('hidden');
    $('session').classList.remove('hidden');
    $('who').textContent = `${email} · ${role}`;
    document.querySelectorAll('.host').forEach(el => el.textContent = location.origin);
    refresh().then(() => refreshKeys()).catch(() => {});
    startPolling();
}

document.addEventListener('DOMContentLoaded', () => {
    $('login-form').addEventListener('submit', signIn);
    $('signup-form').addEventListener('submit', signUp);
    $('key-form').addEventListener('submit', issueKey);
    $('rule-form').addEventListener('submit', createRule);

    $('show-login').addEventListener('click', () => {
        $('login-form').classList.remove('hidden');
        $('signup-form').classList.add('hidden');
        $('show-login').classList.add('active');
        $('show-signup').classList.remove('active');
    });
    $('show-signup').addEventListener('click', () => {
        $('signup-form').classList.remove('hidden');
        $('login-form').classList.add('hidden');
        $('show-signup').classList.add('active');
        $('show-login').classList.remove('active');
    });
    $('logout').addEventListener('click', signOut);

    document.querySelectorAll('[data-sim]').forEach(button => {
        button.addEventListener('click', () => simulate(button.dataset.sim, button));
    });

    document.querySelectorAll('.tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.panel').forEach(p => p.classList.add('hidden'));
            tab.classList.add('active');
            $(`tab-${tab.dataset.tab}`).classList.remove('hidden');
        });
    });

    // Stop polling when the tab is hidden. A background tab hammering a free
    // instance every two seconds is pure waste.
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) stopPolling();
        else if (token) startPolling();
    });
});
