import { useContext, useEffect, useState } from 'react'
import { TeamProjectContext } from './teamRole'
import { idleCandidates, readPresence, snapshotWorkers, workerLabel } from './workerPresence'
import type { WorkerPresence } from './workerPresence'

type State = { project: string; rows: WorkerPresence[] | null; error: string; receivedAt: number }
export default function WorkerAvailabilityPanel({ snapshotId, expectedHash }: { snapshotId?: string; expectedHash?: string }) {
  const project = useContext(TeamProjectContext)
  const [state, setState] = useState<State | null>(null)
  const [refresh, setRefresh] = useState(0)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  useEffect(() => {
    if (!project) return
    let disposed = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let controller: AbortController | undefined
    async function load() {
      controller = new AbortController()
      const deadline = setTimeout(() => controller?.abort(), 10000)
      try {
        const response = await fetch('/api/workers/presence', { signal: controller.signal, cache: 'no-store', headers: { Accept: 'application/json' } })
        if (!response.ok) throw new Error(`Worker availability request failed (HTTP ${response.status})`)
        const rows = readPresence(await response.json(), project!)
        if (!disposed) setState({ project: project!, rows, error: '', receivedAt: Date.now() })
      } catch (cause) {
        if (!disposed) setState({ project: project!, rows: null, error: cause instanceof Error ? cause.message : 'Worker availability unavailable', receivedAt: Date.now() })
      } finally {
        clearTimeout(deadline)
        if (!disposed) timer = setTimeout(load, 10000)
      }
    }
    void load()
    return () => { disposed = true; controller?.abort(); clearTimeout(timer) }
  }, [project, refresh])
  if (!project) return null
  const scoped = state?.project === project ? state : null
  const fresh = scoped?.rows !== null && scoped?.rows !== undefined && now - scoped.receivedAt < 20000
  const rows = fresh ? snapshotWorkers(scoped!.rows!, snapshotId) : []
  const idle = snapshotId && fresh ? idleCandidates(rows, snapshotId, expectedHash).length : 0
  return <section aria-label={snapshotId ? 'Snapshot worker availability' : 'Project worker availability'} style={{ margin: '1rem 0', padding: '1rem', border: '1px solid #cbd5e1', borderRadius: '8px' }}>
    <h2>{snapshotId ? 'Workers for this snapshot' : 'Project workers'}</h2>
    <button onClick={() => setRefresh(value => value + 1)}>Refresh worker status</button>
    {!scoped ? <p role="status">Loading worker availability…</p> : !fresh ? <p role="status">Availability unavailable. {scoped.error || 'The last report refresh is stale.'} No current worker readiness is assumed.</p> : <>
      <p>Last dashboard refresh: {new Date(scoped.receivedAt).toLocaleTimeString()}. Updates approximately every 10 seconds.</p>
      {snapshotId && <p>{idle ? `${idle} worker(s) report Idle with matching snapshot bytes. Dispatch still requires backend validation.` : 'No idle worker with matching snapshot bytes is currently reported. Queued work waits for an eligible worker; it has not started merely because it was queued.'}</p>}
      {!rows.length ? <p>No worker credentials or reports found{snapshotId ? ' for this snapshot' : ' in this project'}.</p> : <ul>{rows.map(row => <li key={row.credentialId} style={{ marginBottom: '1rem', overflowWrap: 'anywhere' }}>
        <strong>{row.workerId} — {workerLabel(row.availability)}</strong>
        <dl><dt>Last authenticated contact</dt><dd>{row.lastContactAt ? new Date(row.lastContactAt).toLocaleString() : 'Never'}</dd>
          <dt>Registered snapshot</dt><dd>{row.snapshotId}</dd>
          <dt>Current execution attempt</dt><dd>{row.currentExecutionId || 'None reported'}</dd>
          <dt>Readiness</dt><dd>{row.readiness === 'BASE_VERIFIED' ? 'Snapshot bytes verified' : row.readiness === 'NOT_READY' ? 'Local snapshot verification failed' : 'Not reported'}</dd>
        </dl>
      </li>)}</ul>}
    </>}
    <p>Offline means no accepted contact for 60 seconds. Idle confirms reported snapshot verification; Docker readiness, capacity and successful execution require separate checks. Presence never changes an execution result.</p>
  </section>
}
