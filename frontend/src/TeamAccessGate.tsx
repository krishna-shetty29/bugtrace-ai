import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { accessApi, setTeamCsrf } from './teamClient'
import { TeamRoleContext } from './teamRole'

type Actor = { id: string; projectId: string; name: string; kind: string; role: string }
type Session = { actor: Actor; csrf: string }
type Issued = { credentialId: string; projectId: string; projectName?: string; accessKey: string; expiresAt: string }
type Credential = { id: string; display_name: string; kind: string; role: string | null; snapshot_id: string | null; worker_id: string | null; revoked: boolean; expires_at: string }
const message = (cause: unknown) => cause instanceof Error ? cause.message : 'Access request failed'

function Secret({ item }: { item: Issued }) {
  return <div><p>{item.projectName || 'Project'}: {item.projectId}<br />Credential: {item.credentialId}<br />Expires: {item.expiresAt}</p><input type="password" readOnly value={item.accessKey} aria-label="New access key" autoComplete="off" /><button onClick={() => void navigator.clipboard.writeText(item.accessKey)}>Copy new access key</button><p>Shown only in this response. Store it privately before leaving this page.</p></div>
}

function Management() {
  const [name, setName] = useState('')
  const [projectName, setProjectName] = useState('')
  const [role, setRole] = useState('VIEWER')
  const [days, setDays] = useState(30)
  const [snapshot, setSnapshot] = useState('')
  const [worker, setWorker] = useState('laptop-worker-01')
  const [credentials, setCredentials] = useState<Credential[]>([])
  const [issued, setIssued] = useState<Issued | null>(null)
  const [audit, setAudit] = useState<unknown>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function act(operation: () => Promise<void>) {
    if (busy) return
    setBusy(true); setError('')
    try { await operation() } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }
  return <details><summary>Project access management</summary>
    {error && <p role="alert">{error}</p>}
    <label>Person or worker name<input value={name} maxLength={80} onChange={event => setName(event.target.value)} /></label>
    <label>Permission<select value={role} onChange={event => setRole(event.target.value)}>{['OWNER', 'INVESTIGATOR', 'REVIEWER', 'VIEWER', 'WORKER'].map(value => <option key={value}>{value}</option>)}</select></label>
    <label>Key lifetime in days (1–90)<input type="number" min={1} max={90} value={days} onChange={event => setDays(Number(event.target.value))} /></label>
    {role === 'WORKER' && <><label>Registered snapshot UUID<input value={snapshot} onChange={event => setSnapshot(event.target.value)} /></label><label>Worker ID<input value={worker} onChange={event => setWorker(event.target.value)} /></label></>}
    <button disabled={busy || !name.trim()} onClick={() => void act(async () => { setIssued(await accessApi<Issued>('credentials', { name, role: role === 'WORKER' ? null : role, snapshotId: role === 'WORKER' ? snapshot : null, workerId: role === 'WORKER' ? worker : null, days })); setCredentials(await accessApi<Credential[]>('credentials')) })}>Create project-scoped access key</button>
    {issued && <Secret item={issued} />}
    <button disabled={busy} onClick={() => void act(async () => setCredentials(await accessApi<Credential[]>('credentials')))}>Load project credentials</button>
    <ul>{credentials.map(item => <li key={item.id}>{item.display_name} · {item.kind} · {item.role || item.worker_id} · {item.revoked ? 'Revoked' : `Expires ${item.expires_at}`} <button disabled={busy || item.revoked} onClick={() => void act(async () => { await accessApi(`credentials/${item.id}/revoke`, {}); setCredentials(await accessApi<Credential[]>('credentials')) })}>Revoke</button></li>)}</ul>
    <p>Role changes use a new credential followed by revoking the old one. Create another owner key before revoking the last owner. Rotate keys before expiry.</p>
    <button disabled={busy} onClick={() => void act(async () => setAudit(await accessApi('audit')))}>Load project audit trail</button>
    {audit !== null && <pre>{JSON.stringify(audit, null, 2)}</pre>}
    <label>New project name<input value={projectName} maxLength={200} onChange={event => setProjectName(event.target.value)} /></label>
    <button disabled={busy || !projectName.trim()} onClick={() => void act(async () => setIssued(await accessApi<Issued>('projects', { name: projectName })))}>Create another project and owner key</button>
  </details>
}

export default function TeamAccessGate({ children }: { children: ReactNode }) {
  const [actor, setActor] = useState<Actor | null>(null)
  const [initialized, setInitialized] = useState(true)
  const [loading, setLoading] = useState(true)
  const [key, setKey] = useState('')
  const [bootstrap, setBootstrap] = useState('')
  const [issued, setIssued] = useState<Issued[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let active = true
    const ended = () => { setActor(null); setTeamCsrf('') }
    window.addEventListener('bugtrace-session-ended', ended)
    async function load() {
      try {
        const status = await accessApi<{ initialized: boolean }>('status')
        if (active) setInitialized(status.initialized)
        if (status.initialized) {
          try { const session = await accessApi<Session>('session'); if (active) { setTeamCsrf(session.csrf); setActor(session.actor) } }
          catch (cause) { if (!(cause instanceof Error) || !cause.message.startsWith('HTTP 401:')) throw cause }
        }
      } catch (cause) { if (active) setError(message(cause)) }
      finally { if (active) setLoading(false) }
    }
    void load()
    return () => { active = false; window.removeEventListener('bugtrace-session-ended', ended) }
  }, [])
  async function act(operation: () => Promise<void>) {
    if (busy) return
    setBusy(true); setError('')
    try { await operation() } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }
  if (loading) return <p>Checking your session…</p>
  return <>
    {error && <p role="alert">{error}</p>}
    {actor ? <><section aria-label="Signed-in project"><p>Signed in: {actor.name} · {actor.role}<br />Project: {actor.projectId}</p><button disabled={busy} onClick={() => void act(async () => { await accessApi('logout', {}); setTeamCsrf(''); setActor(null); setIssued([]) })}>Sign out</button>{actor.role === 'OWNER' && <Management />}</section><TeamRoleContext.Provider value={actor.role}>{children}</TeamRoleContext.Provider></> : <section>
      <h1>Sign in to BugTrace</h1>
      {!initialized && <><p>Initial setup requires the operator's bootstrap secret. It creates owner keys for existing projects once.</p><label>Bootstrap secret<input type="password" value={bootstrap} autoComplete="off" onChange={event => setBootstrap(event.target.value)} /></label><button disabled={busy || !bootstrap} onClick={() => void act(async () => { setIssued(await accessApi<Issued[]>('bootstrap', { secret: bootstrap })); setBootstrap(''); setInitialized(true) })}>Initialize project access</button></>}
      {issued.map(item => <Secret key={item.credentialId} item={item} />)}
      <p>Use your personal project access key. Each key grants access to one project.</p>
      <form onSubmit={event => { event.preventDefault(); void act(async () => { const session = await accessApi<Session>('login', { accessKey: key.trim() }); setKey(''); setTeamCsrf(session.csrf); setActor(session.actor); setIssued([]) }) }}><label>Access key<input type="password" value={key} autoComplete="off" onChange={event => setKey(event.target.value)} /></label><button disabled={busy || !initialized || !key}>Sign in</button></form>
    </section>}
  </>
}
