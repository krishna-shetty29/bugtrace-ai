import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import './App.css'
import SemanticRetrievalPanel from './SemanticRetrievalPanel'
import DocumentContextPanel from './DocumentContextPanel'

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
    <DocumentContextPanel key={"documents-" + id} investigationId={id} />
    <SemanticRetrievalPanel key={id} investigationId={id} />
    <SnapshotSearchPanel investigationId={id} />
    <JavaIndexPanel investigationId={id} />
    <JavaGraphPanel investigationId={id} />
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
type Fixture = { snapshotId: string; projectId: string; commitSha: string; modulePath: string; contentHash: string }
function ManualWorkflow() {
  const fixtures = usePoll<Fixture[]>('/manual-workflow/fixtures', 10000)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const body = Object.fromEntries(new FormData(event.currentTarget).entries())
    setBusy(true); setError('')
    try {
      const report = await api<Investigation>('/manual-workflow/investigations', body)
      window.location.hash = `/investigations/${report.id}`
    } catch (cause) { setError(message(cause)); setBusy(false) }
  }
  return <><h1>Manual checkout workflow</h1>
    <p>Select the imported buggy fixture, enter your report, then create an investigation. Its source is verified before the report is saved.</p>
    <ErrorBox>{error || fixtures.error}</ErrorBox>
    <p>This page uses the same immutable snapshot as the configured worker. Each report and execution has its own ID; each execution gets an isolated experiment copy.</p>
    <form onSubmit={create}>
      <label>Fixture snapshot<select name="snapshotId" required defaultValue=""><option value="" disabled>Select checkout fixture</option>{fixtures.data?.map(fixture => <option key={fixture.snapshotId} value={fixture.snapshotId}>Checkout timeout · {fixture.snapshotId}</option>)}</select></label>
      {fixtures.data?.map(fixture => <details key={fixture.snapshotId}><summary>Captured source identity</summary><dl><dt>Commit</dt><dd>{fixture.commitSha}</dd><dt>Module</dt><dd>{fixture.modulePath}</dd><dt>Content hash</dt><dd>{fixture.contentHash}</dd></dl></details>)}
      <label>Title<input name="title" required maxLength={300} /></label>
      <label>Bug description<textarea name="bugDescription" required maxLength={20000} /></label>
      <label>Expected behavior<textarea name="expectedBehavior" required maxLength={20000} /></label>
      <label>Observed behavior<textarea name="observedBehavior" required maxLength={20000} /></label>
      <button disabled={busy || !fixtures.data?.length}>{busy ? 'Verifying source and saving…' : 'Create investigation from fixture'}</button>
    </form>
    <p>On the next page click Verify snapshot, then queue RUN_SELECTED_TEST with CheckoutIdempotencyTest. Do not reimport an already captured base.</p>
    <p>Need to import the fixture first? <a href="#/new">Create its initial snapshot report</a> and import committed source on that detail page.</p>
  </>
}
type JavaSymbol = { kind: string; name: string; owner: string; signature: string; filePath: string; startLine: number; startColumn: number; endLine: number; endColumn: number; annotations: string[] }
type JavaSourceFile = { filePath: string; packageName: string; parsed: boolean; symbols: JavaSymbol[]; failures: { phase: string; message: string; line: number | null; column: number | null }[] }
type SavedJavaIndex = { snapshotId: string; contentHash: string; index: { parser: string; parserVersion: string; languageLevel: string; status: string; indexedAt: string; javaFiles: number; parsedFiles: number; failedFiles: number; symbolCount: number; files: JavaSourceFile[] } }
function JavaIndexPanel({ investigationId }: { investigationId: string }) {
  const [saved, setSaved] = useState<SavedJavaIndex | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [search, setSearch] = useState('')
  const [kind, setKind] = useState('ALL')
  const [page, setPage] = useState(0)
  async function load(build: boolean) {
    setBusy(true); setError('')
    try {
      const result = await api<SavedJavaIndex>(`/investigations/${investigationId}/java-index`, build ? {} : undefined)
      setSaved(result); setPage(0)
    } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }
  const symbols = saved?.index.files.flatMap(file => file.symbols) || []
  const query = search.trim().toLowerCase()
  const filtered = symbols.filter(symbol => (kind === 'ALL' || symbol.kind === kind) &&
    `${symbol.name} ${symbol.owner} ${symbol.signature} ${symbol.filePath}`.toLowerCase().includes(query))
  return <section aria-label="Java symbol index"><h2>Java symbol index</h2>
    <p>Index Java declarations in the immutable snapshot. This reads source only; it does not run a build or resolve method calls.</p>
    <button disabled={busy} onClick={() => void load(true)}>{busy ? 'Working…' : 'Build Java index'}</button>
    <button disabled={busy} onClick={() => void load(false)}>Load saved index</button>
    <ErrorBox>{error}</ErrorBox>
    {saved && <><p role="status">Status: <strong>{saved.index.status}</strong> · Java files: {saved.index.javaFiles} · Parsed: {saved.index.parsedFiles} · Failed: {saved.index.failedFiles} · Symbols: {saved.index.symbolCount}</p>
      <p>{saved.index.parser} {saved.index.parserVersion} · {saved.index.languageLevel} · {stamp(saved.index.indexedAt)}</p>
      <p className="identifier">Indexed snapshot: {saved.snapshotId}<br />Content hash: {saved.contentHash}</p>
      <button onClick={() => download(`${saved.snapshotId}-java-index.json`, JSON.stringify(saved, null, 2), 'application/json')}>Download Java index JSON</button>
      <h3>Files and parser failures</h3>
      {saved.index.files.length === 0 && <p>No Java files were found. This is an EMPTY index, not a successful Java analysis.</p>}
      {saved.index.files.map(file => <details key={file.filePath}><summary>{file.parsed ? 'Parsed' : 'FAILED'}: {file.filePath} ({file.symbols.length} symbols)</summary>
        <p>Package: {file.packageName || '(default package or unavailable)'}</p>
        {file.failures.map((failure, index) => <p className="error" key={index}>{failure.phase}: {failure.message} {failure.line !== null ? `(line ${failure.line}, column ${failure.column ?? '?'})` : ''}</p>)}
      </details>)}
      <h3>Symbols</h3>
      <label>Search name, owner, signature or file<input value={search} onChange={event => { setSearch(event.target.value); setPage(0) }} /></label>
      <label>Symbol kind<select value={kind} onChange={event => { setKind(event.target.value); setPage(0) }}><option>ALL</option>{Array.from(new Set(symbols.map(symbol => symbol.kind))).sort().map(value => <option key={value}>{value}</option>)}</select></label>
      <p>{filtered.length} matching symbols. Showing up to 50 per page.</p>
      <div className="table"><table><thead><tr><th>Kind</th><th>Name / signature</th><th>Owner</th><th>File and range</th><th>Annotations</th></tr></thead><tbody>
        {filtered.slice(page * 50, (page + 1) * 50).map((symbol, index) => <tr key={`${symbol.filePath}:${symbol.startLine}:${symbol.startColumn}:${symbol.kind}:${index}`}><td>{symbol.kind}</td><td>{symbol.name}<br /><code>{symbol.signature}</code></td><td>{symbol.owner || '(default package)'}</td><td>{symbol.filePath}<br />{symbol.startLine}:{symbol.startColumn}–{symbol.endLine}:{symbol.endColumn}</td><td>{symbol.annotations.join(', ') || '—'}</td></tr>)}
      </tbody></table></div>
      <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous symbols</button>
      <button disabled={(page + 1) * 50 >= filtered.length} onClick={() => setPage(page + 1)}>Next symbols</button>
    </>}
  </section>
}
type GraphNode = { id: string; kind: string; label: string; filePath: string; startLine: number; endLine: number }
type GraphEdge = { id: string; kind: string; from: string; to: string; confidence: string; reason: string; filePath: string; line: number; column: number }
type SavedGraph = { snapshotId: string; contentHash: string; graph: { algorithmVersion: string; parserVersion: string; status: string; builtAt: string; javaFiles: number; failedFiles: number; uncertainEdges: number; nodes: GraphNode[]; edges: GraphEdge[]; diagnostics: JavaSourceFile[]; limitations: string[] } }
type ImpactResult = { root: string; maxDepth: number; includeUncertain: boolean; truncated: boolean; reached: { nodeId: string; distance: number; uncertain: boolean; path: GraphEdge[] }[] }
function JavaGraphPanel({ investigationId }: { investigationId: string }) {
  const [saved, setSaved] = useState<SavedGraph | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState('')
  const [kind, setKind] = useState('ALL')
  const [direction, setDirection] = useState('OUTGOING')
  const [depth, setDepth] = useState(3)
  const [includeUncertain, setIncludeUncertain] = useState(true)
  const [impact, setImpact] = useState<ImpactResult | null>(null)
  async function load(build: boolean) {
    setBusy(true); setError(''); setImpact(null)
    try {
      const result = await api<SavedGraph>(`/investigations/${investigationId}/java-graph`, build ? {} : undefined)
      setSaved(result); setSelected('')
    } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }
  async function traverse() {
    setBusy(true); setError(''); setImpact(null)
    try {
      setImpact(await api<ImpactResult>(`/investigations/${investigationId}/java-graph/impact?nodeId=${encodeURIComponent(selected)}&depth=${depth}&includeUncertain=${includeUncertain}`))
    } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }
  function focus(id: string) { setSelected(id); setImpact(null) }
  const nodes = saved?.graph.nodes || []
  const byId = new Map(nodes.map(node => [node.id, node]))
  const focused = byId.get(selected)
  const filtered = nodes.filter(node => `${node.label} ${node.filePath}`.toLowerCase().includes(search.toLowerCase()))
  const relationships = (saved?.graph.edges || []).filter(edge => (direction === 'OUTGOING' ? edge.from === selected : edge.to === selected) && (kind === 'ALL' || edge.kind === kind))
  const preview = relationships.slice(0, 12)
  const height = Math.max(180, preview.length * 76 + 40)
  return <section aria-label="Java relationship graph"><h2>Java relationship graph</h2>
    <p>Explore source relationships and potential impact. Dashed links are uncertain; source references do not prove runtime calls or test coverage.</p>
    <button disabled={busy} onClick={() => void load(true)}>{busy ? 'Working…' : 'Build relationship graph'}</button>
    <button disabled={busy} onClick={() => void load(false)}>Load saved graph</button>
    <ErrorBox>{error}</ErrorBox>
    {saved && <><p role="status">Source parsing: <strong>{saved.graph.status}</strong> · Nodes: {nodes.length} · Edges: {saved.graph.edges.length} · Uncertain edges: {saved.graph.uncertainEdges} · Failed files: {saved.graph.failedFiles}</p>
      <p>Built: {stamp(saved.graph.builtAt)}. COMPLETE describes source parsing, not complete relationship resolution.</p>
      <button onClick={() => download(`${saved.snapshotId}-java-graph.json`, JSON.stringify(saved, null, 2), 'application/json')}>Download graph JSON</button>
      <details><summary>Analysis limitations and failed files</summary><ul>{saved.graph.limitations.map(item => <li key={item}>{item}</li>)}</ul>{saved.graph.diagnostics.map(file => <div key={file.filePath}><strong>{file.filePath}</strong><Json value={file.failures} /></div>)}</details>
      <label>Find a node<input placeholder="CheckoutService or PaymentGateway" value={search} onChange={event => setSearch(event.target.value)} /></label>
      <p>{filtered.length} matching nodes; first 100 shown. Narrow the search to find more specific methods.</p>
      <div className="table" style={{ maxHeight: 320, overflow: 'auto' }}><table><thead><tr><th>Kind</th><th>Node</th><th>Location</th></tr></thead><tbody>{filtered.slice(0, 100).map(node => <tr key={node.id}><td>{node.kind}</td><td><button type="button" onClick={() => focus(node.id)}>{node.label}</button></td><td>{node.filePath}:{node.startLine || 'unresolved'}</td></tr>)}</tbody></table></div>
      {focused && <><h3>Selected: {focused.label}</h3>
        <label>Relationship direction<select value={direction} onChange={event => setDirection(event.target.value)}><option>OUTGOING</option><option>INCOMING</option></select></label>
        <label>Relationship kind<select value={kind} onChange={event => setKind(event.target.value)}><option>ALL</option>{Array.from(new Set(saved.graph.edges.map(edge => edge.kind))).sort().map(value => <option key={value}>{value}</option>)}</select></label>
        <p>{relationships.length} matching relationships. Diagram shows up to 12; table shows up to 100. Download JSON for all edges.</p>
        {preview.length > 0 && <div style={{ overflow: 'auto', maxHeight: 600 }}><svg width="980" height={height} viewBox={`0 0 980 ${height}`} role="img" aria-label="Selected node relationships; dashed lines indicate uncertainty">
          <defs><marker id="relationship-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8" fill="#496077" /></marker></defs>
          <rect x="10" y={height / 2 - 28} width="300" height="56" rx="6" fill="#142944" /><text x="22" y={height / 2 + 5} fill="white" fontSize="12"><title>{focused.label}</title>{focused.label.length > 36 ? `${focused.label.slice(0, 33)}…` : focused.label}</text>
          {preview.map((edge, index) => {
            const other = byId.get(direction === 'OUTGOING' ? edge.to : edge.from)
            const y = index * 76 + 35
            return <g key={edge.id}><title>{`${edge.kind}: ${edge.confidence}. ${edge.reason}`}</title>
              <line x1={direction === 'OUTGOING' ? 310 : 620} y1={direction === 'OUTGOING' ? height / 2 : y} x2={direction === 'OUTGOING' ? 620 : 310} y2={direction === 'OUTGOING' ? y : height / 2} stroke="#496077" strokeWidth="1.5" strokeDasharray={edge.confidence === 'DECLARED' ? undefined : '6 4'} markerEnd="url(#relationship-arrow)" />
              <text x="340" y={y - 12} fontSize="10" fill="#172640">{edge.kind} · {edge.confidence}</text><rect x="620" y={y - 24} width="350" height="48" rx="6" fill="#e3ebf8" /><text x="630" y={y + 4} fontSize="12" fill="#172640">{(other?.label || edge.to).slice(0, 44)}</text>
            </g>
          })}
        </svg></div>}
        <div className="table"><table><thead><tr><th>Relationship</th><th>Related node</th><th>Confidence and reason</th><th>Evidence</th></tr></thead><tbody>{relationships.slice(0, 100).map(edge => {
          const other = byId.get(direction === 'OUTGOING' ? edge.to : edge.from)
          return <tr key={edge.id}><td>{edge.kind}</td><td><button onClick={() => focus(other?.id || '')}>{other?.label || '(missing node)'}</button></td><td><strong>{edge.confidence}</strong><br />{edge.reason}</td><td>{edge.filePath}:{edge.line}:{edge.column}</td></tr>
        })}</tbody></table></div>
        <h3>Potential impact: incoming traversal</h3><p>Follow references backward to potential callers, dependent types and tests. This uses all relationship kinds, independently of the diagram filter.</p>
        <label>Maximum depth<select value={depth} onChange={event => { setDepth(Number(event.target.value)); setImpact(null) }}>{[1, 2, 3, 4, 5].map(value => <option key={value}>{value}</option>)}</select></label>
        <label><input type="checkbox" style={{ width: 'auto', display: 'inline' }} checked={includeUncertain} onChange={event => { setIncludeUncertain(event.target.checked); setImpact(null) }} /> Include possible and unresolved edges</label>
        <button disabled={busy} onClick={() => void traverse()}>Find potential impact</button>
        {impact && <><p>{impact.reached.length} nodes reached. {impact.truncated ? 'Stopped at the 500-node limit.' : 'Traversal finished within the selected depth.'} One shortest discovery path is retained per node.</p>
          <div className="table"><table><thead><tr><th>Potentially affected node</th><th>Distance</th><th>Path uncertainty</th><th>Evidence path</th></tr></thead><tbody>{impact.reached.map(item => <tr key={item.nodeId}><td><button onClick={() => focus(item.nodeId)}>{byId.get(item.nodeId)?.label || item.nodeId}</button></td><td>{item.distance}</td><td>{item.uncertain ? 'Contains uncertain edges' : 'Declared source relations only'}</td><td><details><summary>Show path</summary><Json value={item.path} /></details></td></tr>)}</tbody></table></div>
        </>}
      </>}
    </>}
  </section>
}
type SearchHit = { kind: string; origin: string; filePath: string; executionId: string; line: number; column: number; name: string; snippet: string; score: number }
type SearchResults = { investigationId: string; snapshotId: string; contentHash: string; query: string; kind: string; limit: number; totalMatches: number; truncated: boolean; symbolsAvailable: boolean; failedJavaFiles: number; sourceFiles: number; textFiles: number; executionsSearched: number; executionWindow: number; notices: { filePath: string; message: string }[]; hits: SearchHit[] }
function SnapshotSearchPanel({ investigationId }: { investigationId: string }) {
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState('ALL')
  const [limit, setLimit] = useState(50)
  const [results, setResults] = useState<SearchResults | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [])
  async function search(text = query) {
    if (busy) return
    const request = new AbortController()
    pending.current?.abort(); pending.current = request
    setBusy(true); setError(''); setResults(null)
    try {
      const value = await api<SearchResults>(`/investigations/${investigationId}/search?q=${encodeURIComponent(text)}&kind=${kind}&limit=${limit}`, undefined, request.signal)
      if (!request.signal.aborted) setResults(value)
    } catch (cause) { if (!request.signal.aborted) setError(message(cause)) }
    finally { if (!request.signal.aborted) setBusy(false) }
  }
  const origins: Record<string, string> = { SYMBOL: 'Java symbol', SOURCE: 'Source text', SNAPSHOT: 'File path', PARSER: 'Parser error', EXECUTION_STDOUT: 'Execution stdout', EXECUTION_STDERR: 'Execution stderr', EXECUTION_TECHNICAL_ERROR: 'Execution error' }
  return <section aria-label="Text and symbol search"><h2>Text and symbol search</h2>
    <p>Search this snapshot and this investigation’s saved execution errors. Matching is case-insensitive and literal; multiple words form a phrase.</p>
    <form onSubmit={event => { event.preventDefault(); void search() }}>
      <label>Search query<input required minLength={2} maxLength={200} disabled={busy} value={query} onChange={event => setQuery(event.target.value)} placeholder="checkout, retry, timeout, charge, idempotency" /></label>
      <label>Search in<select disabled={busy} value={kind} onChange={event => setKind(event.target.value)}><option value="ALL">All sources</option><option value="CLASS">Class / type names</option><option value="METHOD">Method names</option><option value="FILE">File paths</option><option value="TEXT">Source text / keywords</option><option value="ERROR">Errors / stored logs</option></select></label>
      <label>Maximum results<select disabled={busy} value={limit} onChange={event => setLimit(Number(event.target.value))}>{[10, 25, 50, 100].map(value => <option key={value}>{value}</option>)}</select></label>
      <button disabled={busy}>{busy ? 'Searching…' : 'Search'}</button>
    </form>
    <p>Try: {['checkout', 'retry', 'timeout', 'charge', 'idempotency'].map(word => <button type="button" disabled={busy} key={word} onClick={() => { setQuery(word); void search(word) }}>{word}</button>)}</p>
    <ErrorBox>{error}</ErrorBox>
    {results && <><p role="status"><strong>{results.totalMatches} matches</strong> for “{results.query}” in {results.kind}. Showing {results.hits.length}{results.truncated ? '; narrow the query or increase the result limit to see more.' : '.'}</p>
      {!results.symbolsAvailable && <p className="error">Java symbols are not available. Build the Java symbol index below, then search again. Source text and file paths can still be searched.</p>}
      {results.failedJavaFiles > 0 && <p className="error">{results.failedJavaFiles} Java files failed parsing in the saved index. Their source text remains searchable, but their symbols are unavailable. Inspect the Java index diagnostics.</p>}
      <p>Text files read: {results.textFiles}. Error search considers up to {results.executionWindow} recent executions with saved evidence from this investigation; {results.executionsSearched} were read for this query. It does not search other reports or live worker output.</p>
      <details><summary>Search scope and read notices</summary><p className="identifier">Snapshot: {results.snapshotId}<br />Content hash: {results.contentHash}</p><p>Source files scanned for paths/text: {results.sourceFiles}. Symbol names come from the saved Java index. Supported source text includes Java, XML, properties, Markdown, JSON, SQL and common frontend/configuration extensions.</p>{results.notices.length ? <ul>{results.notices.map((notice, index) => <li key={index}>{notice.filePath}: {notice.message}</li>)}</ul> : <p>No text-read warnings.</p>}</details>
      <button onClick={() => download(`${results.snapshotId}-search-results.json`, JSON.stringify(results, null, 2), 'application/json')}>Download search results JSON</button>
      {results.hits.length === 0 ? <p>No matches in the selected scope. Try another query or choose All sources. Error results require a completed execution with saved output in this investigation.</p> : <div className="table"><table><thead><tr><th>Kind / origin</th><th>Name and location</th><th>Matching text</th></tr></thead><tbody>{results.hits.map((hit, index) => <tr key={`${hit.origin}:${hit.filePath}:${hit.executionId}:${hit.line}:${hit.kind}:${index}`}>
        <td>{hit.kind}<br />{origins[hit.origin] || hit.origin}</td><td>{hit.name && <strong>{hit.name}<br /></strong>}{hit.executionId ? <><a href={`#/executions/${hit.executionId}`}>Open execution</a><br />{hit.executionId}<br />Log line {hit.line}:{hit.column}</> : <>{hit.filePath}{hit.line > 0 ? `:${hit.line}:${hit.column}` : ''}</>}</td><td><pre style={{ margin: 0 }}>{hit.snippet}</pre></td>
      </tr>)}</tbody></table></div>}
    </>}
  </section>
}
export default function App() {
  const [route, setRoute] = useState(window.location.hash.slice(1) || '/')
  useEffect(() => { const change = () => setRoute(window.location.hash.slice(1) || '/'); window.addEventListener('hashchange', change); return () => window.removeEventListener('hashchange', change) }, [])
  const parts = route.split('/').filter(Boolean)
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
  let page: ReactNode
  if (route === '/' || route === '/investigations') page = <Investigations />
  else if (route === '/new') page = <NewInvestigation />
  else if (route === '/workflow') page = <ManualWorkflow />
  else if (route === '/executions' || route === '/artifacts') page = <><h1>{parts[0] === 'artifacts' ? 'Artifacts' : 'Open execution'}</h1><p>Enter a backend job ID. The current API does not list execution history.</p><OpenExecution artifacts={parts[0] === 'artifacts'} /></>
  else if (parts.length === 2 && uuid.test(parts[1]) && parts[0] === 'investigations') page = <InvestigationDetail id={parts[1]} />
  else if (parts.length === 2 && uuid.test(parts[1]) && ['executions', 'artifacts'].includes(parts[0])) page = <Execution id={parts[1]} artifacts={parts[0] === 'artifacts'} />
  else page = <h1>Page not found</h1>
  return <><header><a className="brand" href="#/">BugTrace AI</a><nav aria-label="Main navigation"><a href="#/workflow">Manual workflow</a><a href="#/">Investigations</a><a href="#/new">New investigation</a><a href="#/executions">Execution detail</a><a href="#/artifacts">Artifacts</a></nav></header><main key={route}>{page}</main></>
}
