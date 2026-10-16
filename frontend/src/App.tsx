import { useEffect, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import './App.css'

type Investigation = { id: string; projectId: string; snapshotId: string; title: string; workflowState: string; outcome: string | null; bugDescription: string; expectedBehavior: string; observedBehavior: string; createdAt: string; updatedAt: string }
type Project = { id: string; name: string }
type Job = { executionId: string; investigationId: string; snapshotId: string; queueState: string; operation: string; testSelector: string | null; attemptCount: number; maxAttempts: number; technicalError: string | null; progress: unknown; result: Record<string, unknown> | null; attempts: unknown[] }
type Logs = { stdout: string | null; stderr: string | null }

async function api<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method: body === undefined ? 'GET' : 'POST', signal,
    headers: body === undefined ? { Accept: 'application/json' } : { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1500) || response.statusText}`)
  return JSON.parse(text) as T
}

function usePoll<T>(path: string, interval = 3000) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState('')
  const [updated, setUpdated] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function refresh() {
      try {
        const value = await api<T>(path, undefined, controller.signal)
        if (!controller.signal.aborted) { setData(value); setError(''); setUpdated(new Date().toLocaleTimeString()) }
      } catch (cause) {
        if (!controller.signal.aborted) setError(message(cause))
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(refresh, interval)
      }
    }
    void refresh()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [path, interval])
  return { data, error, updated }
}
function message(cause: unknown) { return cause instanceof Error ? cause.message : 'Request failed' }
function ErrorBox({ children }: { children: ReactNode }) { return children ? <p role="alert" className="error">{children}</p> : null }
function Json({ value }: { value: unknown }) { return <pre>{JSON.stringify(value, null, 2)}</pre> }
function stamp(value: string) { return new Date(value).toLocaleString() }
function download(name: string, value: string, type = 'text/plain') {
  const url = URL.createObjectURL(new Blob([value], { type }))
  const link = document.createElement('a'); link.href = url; link.download = name; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
function Investigations() {
  const { data, error, updated } = usePoll<Investigation[]>('/investigations')
  return <><h1>Investigations</h1><p>Polls every three seconds. Last successful refresh: {updated || 'Waiting…'}</p><ErrorBox>{error}</ErrorBox>
    {!data ? <p>Loading investigations…</p> : data.length === 0 ? <p>No investigations yet. <a href="#/new">Create one</a>.</p> : <div className="table"><table><thead><tr><th>Report</th><th>Workflow state</th><th>Outcome</th><th>Updated</th></tr></thead><tbody>{data.map(item => <tr key={item.id}><td><a href={`#/investigations/${item.id}`}>{item.title}</a></td><td>{item.workflowState}</td><td>{item.outcome || 'Not recorded'}</td><td>{stamp(item.updatedAt)}</td></tr>)}</tbody></table></div>}
  </>
}
function NewInvestigation() {
  const projects = usePoll<Project[]>('/projects', 10000)
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const [projectName, setProjectName] = useState('')
  async function createProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError('')
    try { await api('/projects', { name: projectName, description: '' }); setProjectName('') }
    catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const fields = new FormData(event.currentTarget)
    const body = Object.fromEntries(fields.entries()); setBusy(true); setError('')
    try { const item = await api<Investigation>('/investigations', body); window.location.hash = `/investigations/${item.id}` }
    catch (cause) { setError(message(cause)); setBusy(false) }
  }
  return <><h1>New investigation</h1><ErrorBox>{error || projects.error}</ErrorBox>
    <details><summary>Create a project first if needed</summary><form onSubmit={createProject}><label>Project name<input required maxLength={200} value={projectName} onChange={event => setProjectName(event.target.value)} /></label><button disabled={busy}>Create project</button><p>New projects appear in the selector within ten seconds.</p></form></details>
    <form onSubmit={submit}>
      <label>Project<select name="projectId" required defaultValue=""><option value="" disabled>Select a project</option>{projects.data?.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
      <label>Title<input name="title" required maxLength={300} /></label>
      <label>Local repository path<input name="repositoryUrl" required maxLength={1000} placeholder="C:\Users\ADMIN\bugtrace-ai" /></label>
      <label>Full committed revision<input name="commitSha" required pattern="([0-9a-fA-F]{40}|[0-9a-fA-F]{64})" placeholder="Full Git commit hash" /></label>
      <label>Bug description<textarea name="bugDescription" required maxLength={20000} /></label>
      <label>Expected behavior<textarea name="expectedBehavior" required maxLength={20000} /></label>
      <label>Observed behavior<textarea name="observedBehavior" required maxLength={20000} /></label>
      <p>This saves the report and snapshot identity. Import its source on the next page.</p><button disabled={busy || !projects.data?.length}>{busy ? 'Saving…' : 'Create investigation'}</button>
    </form></>
}
function InvestigationDetail({ id }: { id: string }) {
  const { data, error, updated } = usePoll<Investigation>(`/investigations/${id}`)
  const [actionError, setActionError] = useState(''); const [busy, setBusy] = useState(false); const [notice, setNotice] = useState('')
  const [verification, setVerification] = useState<unknown>(null)
  async function importSource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const body = Object.fromEntries(new FormData(event.currentTarget).entries()); setBusy(true); setActionError(''); setNotice('')
    try { await api(`/investigations/${id}/snapshot/import`, body); setNotice('Source imported. Verify the snapshot before running a test.') }
    catch (cause) { setActionError(message(cause)) } finally { setBusy(false) }
  }
  async function verify() {
    setBusy(true); setActionError('')
    try { setVerification(await api(`/investigations/${id}/snapshot/verify`)) }
    catch (cause) { setActionError(message(cause)) } finally { setBusy(false) }
  }
  async function queue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const fields = new FormData(event.currentTarget); const operation = String(fields.get('operation')); setBusy(true); setActionError('')
    try {
      const job = await api<Job>(`/investigations/${id}/executions`, { operation, ...(operation === 'RUN_SELECTED_TEST' ? { testSelector: fields.get('testSelector') } : {}), timeoutSeconds: Number(fields.get('timeoutSeconds')) })
      window.location.hash = `/executions/${job.executionId}`
    } catch (cause) { setActionError(message(cause)); setBusy(false) }
  }
  return <><ErrorBox>{error || actionError}</ErrorBox>{!data ? <p>Loading investigation…</p> : <><h1>{data.title}</h1><p>State: <strong>{data.workflowState}</strong> · Outcome: <strong>{data.outcome || 'Not recorded'}</strong></p><p>Last refresh: {updated}. Execution classifications do not automatically change the bug outcome.</p><dl><dt>Investigation ID</dt><dd>{data.id}</dd><dt>Project ID</dt><dd>{data.projectId}</dd><dt>Snapshot ID</dt><dd>{data.snapshotId}</dd><dt>Bug description</dt><dd>{data.bugDescription}</dd><dt>Expected behavior</dt><dd>{data.expectedBehavior}</dd><dt>Observed behavior</dt><dd>{data.observedBehavior}</dd></dl>
    <h2>Import committed source</h2><p>For a snapshot already imported, use Verify snapshot. Import does not overwrite an existing base.</p>
    <form onSubmit={importSource}><label>Repository path<input name="repositoryPath" required maxLength={1000} placeholder="C:\Users\ADMIN\bugtrace-ai" /></label><label>Revision matching this snapshot<input name="revision" required maxLength={200} placeholder="checkout-buggy-v1" /></label><label>Module path<input name="modulePath" required defaultValue="fixtures/checkout-timeout" maxLength={200} /></label><button disabled={busy}>Import source</button></form>
    <p role="status">{notice}</p><button disabled={busy} onClick={() => void verify()}>Verify snapshot</button>{verification !== null && <Json value={verification} />}
    <h2>Run an execution</h2><p>The worker must be running and registered for this snapshot. Use your trusted checkout fixture.</p>
    <form onSubmit={queue}><label>Operation<select name="operation"><option>RUN_SELECTED_TEST</option><option>RUN_TEST_SUITE</option><option>COMPILE</option></select></label><label>Test class (selected-test operation only)<input name="testSelector" defaultValue="CheckoutIdempotencyTest" /></label><label>Deadline in seconds<input name="timeoutSeconds" type="number" min={1} max={600} required defaultValue={300} /></label><button disabled={busy}>Queue execution</button></form>
    <h2>Open an existing execution</h2><OpenExecution /></> }</>
}
function OpenExecution({ artifacts = false }: { artifacts?: boolean }) {
  function open(event: FormEvent<HTMLFormElement>) { event.preventDefault(); const id = String(new FormData(event.currentTarget).get('id')).trim(); window.location.hash = `/${artifacts ? 'artifacts' : 'executions'}/${id}` }
  return <form onSubmit={open}><label>Backend execution ID<input name="id" required pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" /></label><button>Open {artifacts ? 'artifacts' : 'execution'}</button></form>
}
function Execution({ id, artifacts = false }: { id: string; artifacts?: boolean }) {
  const job = usePoll<Job>(`/executions/${id}`)
  const logs = usePoll<Logs>(`/executions/${id}/logs`, 5000)
  return <><h1>{artifacts ? 'Artifacts' : 'Execution detail'}</h1><p className="identifier">{id}</p><ErrorBox>{job.error || logs.error}</ErrorBox>
    {!job.data ? <p>Loading execution…</p> : <><p>Queue state: <strong>{job.data.queueState}</strong> · Classification: <strong>{String(job.data.result?.classification || 'Pending')}</strong></p><p>Attempt {job.data.attemptCount} of {job.data.maxAttempts}. Last refresh: {job.updated}</p><p><a href={`#/investigations/${job.data.investigationId}`}>Investigation</a> · <a href={`#/${artifacts ? 'executions' : 'artifacts'}/${id}`}>{artifacts ? 'Execution detail' : 'Artifacts'}</a></p>
    {job.data.technicalError && <ErrorBox>{job.data.technicalError}</ErrorBox>}
    {artifacts ? <><h2>Saved execution evidence</h2><p>Download the stored metadata and logs below. JUnit report summaries and hashes, when present, are included in the result metadata.</p><button disabled={!job.data.result} onClick={() => download(`${id}-evidence.json`, JSON.stringify(job.data?.result, null, 2), 'application/json')}>Download evidence JSON</button><button disabled={!logs.data} onClick={() => download(`${id}-stdout.log`, logs.data?.stdout || '')}>Download stdout</button><button disabled={!logs.data} onClick={() => download(`${id}-stderr.log`, logs.data?.stderr || '')}>Download stderr</button><p>Raw JUnit XML and reproduction source remain in the snapshot and worker workspace. This backend does not yet offer their download endpoints.</p></> : <><h2>Progress</h2><Json value={job.data.progress} /><h2>Attempts</h2><Json value={job.data.attempts} /></>}
    <h2>Result and test reports</h2>{job.data.result ? <Json value={job.data.result} /> : <p>No final result stored yet. A completed queue job can contain a failed test; inspect its classification.</p>}
    </>}
    <h2>Standard output</h2><pre>{logs.data?.stdout || 'No stored output yet.'}</pre><h2>Standard error</h2><pre>{logs.data?.stderr || 'No stored error output.'}</pre><p>Polling retrieves stored logs. Streaming logs will be added later.</p></>
}
export default function App() {
  const [route, setRoute] = useState(window.location.hash.slice(1) || '/')
  useEffect(() => { const change = () => setRoute(window.location.hash.slice(1) || '/'); window.addEventListener('hashchange', change); return () => window.removeEventListener('hashchange', change) }, [])
  const parts = route.split('/').filter(Boolean)
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
  let page: ReactNode
  if (route === '/' || route === '/investigations') page = <Investigations />
  else if (route === '/new') page = <NewInvestigation />
  else if (route === '/executions' || route === '/artifacts') page = <><h1>{parts[0] === 'artifacts' ? 'Artifacts' : 'Open execution'}</h1><p>Enter a backend job ID. The current API does not list execution history.</p><OpenExecution artifacts={parts[0] === 'artifacts'} /></>
  else if (parts.length === 2 && uuid.test(parts[1]) && parts[0] === 'investigations') page = <InvestigationDetail id={parts[1]} />
  else if (parts.length === 2 && uuid.test(parts[1]) && ['executions', 'artifacts'].includes(parts[0])) page = <Execution id={parts[1]} artifacts={parts[0] === 'artifacts'} />
  else page = <h1>Page not found</h1>
  return <><header><a className="brand" href="#/">BugTrace AI</a><nav aria-label="Main navigation"><a href="#/">Investigations</a><a href="#/new">New investigation</a><a href="#/executions">Execution detail</a><a href="#/artifacts">Artifacts</a></nav></header><main key={route}>{page}</main></>
}
