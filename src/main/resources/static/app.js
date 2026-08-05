'use strict';

// The token lives in memory only. sessionStorage would survive a refresh, but
// it is also readable by any script on the page, and this is a demo that does
// not need the convenience.
let token = null;
let role = null;

const $ = (id) => document.getElementById(id);

async function api(path) {
    const response = await fetch(path, {
        headers: { 'Authorization': `Bearer ${token}` }
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
    const error = $('login-error');

    button.disabled = true;
    button.textContent = 'Signing in…';
    error.classList.add('hidden');

    try {
        const response = await fetch('/v1/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: $('email').value, password: $('password').value })
        });

        if (!response.ok) {
            // The API deliberately returns the same message for an unknown
            // account and a wrong password, so there is nothing more specific
            // to show here.
            throw new Error('Invalid credentials');
        }

        const session = await response.json();
        token = session.token;
        role = session.role;

        $('login-view').classList.add('hidden');
        $('app-view').classList.remove('hidden');
        $('session').classList.remove('hidden');
        $('who').textContent = `${$('email').value} · ${role}`;

        await refresh();
    } catch (e) {
        error.textContent = e.message;
        error.classList.remove('hidden');
    } finally {
        button.disabled = false;
        button.textContent = 'Sign in';
    }
}

function signOut() {
    token = null;
    role = null;
    $('app-view').classList.add('hidden');
    $('session').classList.add('hidden');
    $('login-view').classList.remove('hidden');
}

async function refresh() {
    const [alerts, events, rules] = await Promise.all([
        api('/v1/management/alerts?size=50'),
        api('/v1/management/events?size=50'),
        api('/v1/management/rules')
    ]);

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
        body.innerHTML = row(7, 'No alerts yet.');
        return;
    }

    for (const alert of alerts) {
        const canAct = role === 'ADMIN' || role === 'ANALYST';
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><span class="sev ${alert.severity}">${alert.severity}</span></td>
            <td><span class="status ${alert.status}">${alert.status}</span></td>
            <td><code>${escape(alert.dedupeKey)}</code></td>
            <td class="num">${alert.occurrenceCount}</td>
            <td>${time(alert.firstSeenAt)}</td>
            <td>${time(alert.lastSeenAt)}</td>
            <td>${canAct && alert.status !== 'RESOLVED'
                    ? `<button class="ghost" data-resolve="${alert.id}">Resolve</button>` : ''}</td>`;
        body.appendChild(tr);
    }

    body.querySelectorAll('[data-resolve]').forEach(button => {
        button.addEventListener('click', () => resolve(button.dataset.resolve));
    });
}

async function resolve(alertId) {
    await fetch(`/v1/management/alerts/${alertId}/resolve`, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` }
    });
    await refresh();
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
            <td>${time(event.occurredAt)}</td>`;
        body.appendChild(tr);
    }
}

function renderRules(rules) {
    const body = $('rules-body');
    body.innerHTML = rules.length ? '' : row(7, 'No rules configured.');

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
            <td><span class="sev ${rule.alertSeverity}">${rule.alertSeverity}</span></td>`;
        body.appendChild(tr);
    }
}

const row = (cols, text) => `<tr><td colspan="${cols}" class="muted">${text}</td></tr>`;

const time = (iso) => new Date(iso).toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
});

// Values come from the database and are rendered into innerHTML, so anything
// an agent could put in a field has to be neutralised first.
function escape(value) {
    const div = document.createElement('div');
    div.textContent = String(value);
    return div.innerHTML;
}

document.addEventListener('DOMContentLoaded', () => {
    $('login-form').addEventListener('submit', signIn);
    $('logout').addEventListener('click', signOut);

    document.querySelectorAll('.tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.panel').forEach(p => p.classList.add('hidden'));
            tab.classList.add('active');
            $(`tab-${tab.dataset.tab}`).classList.remove('hidden');
        });
    });
});
