import { useState } from 'react'

type Source = { id: string; origin: string; sourceRef: string; startLine: number; endLine: number }
type Audit = { id: string; role: string; tool: string; state: string; message?: string; execution_id?: string | null; result?: unknown }
const allowed = [
  ['searchCode', 'readFile', 'readEvidence'],
  ['createTest', 'requestExperiment', 'readExecutionResults'],
  ['readEvidence', 'readExecutionResults', 'readTestReports'],
  ['readDiagnosis', 'readRelevantFiles', 'proposePatch'],
  ['requestApprovedChecks', 'readTestReports', 'createVerificationSummary'],
]
export default function RoleToolsPanel({ investigationId, runId, role, state, executionEnabled, sources, executionIds }: {
  investigationId: string; runId: string; role: number; state: string; executionEnabled: boolean; sources: Source[]; executionIds: string[]
}) {
  const names = allowed[role] ?? []
  const [tool, setTool] = useState(names[0] ?? 'searchCode')
  const [argumentsText, setArgumentsText] = useState('{"query":"charge"}')
  const [requestId, setRequestId] = useState<string>(() => crypto.randomUUID())
  const [enabled, setEnabled] = useState(executionEnabled)
  const [audit, setAudit] = useState<Audit[]>([])
  const [result, setResult] = useState<Audit | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function api<T>(path: string, body?: unknown): Promise<T> {
    const response = await fetch(`/api/investigations/${investigationId}/agent-runs/${runId}${path}`, {
      method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await response.text()
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1500)}`)
    return JSON.parse(text) as T
  }
  function template(name: string) {
    const source = sources.find(item => item.origin === 'CURRENT_SNAPSHOT_CODE')
    const execution = [...audit].reverse().find(item => item.execution_id)?.execution_id ?? executionIds[0] ?? 'PASTE_SCOPED_EXECUTION_UUID'
    const choices: Record<string, unknown> = {
      searchCode: { query: 'charge' }, readEvidence: { evidenceId: sources[0]?.id ?? 'PASTE_SELECTED_EVIDENCE_ID' },
      readFile: { path: source?.sourceRef ?? 'PASTE_SELECTED_PATH', startLine: source?.startLine ?? 1, endLine: Math.min(source?.endLine ?? 10, (source?.startLine ?? 1) + 20) },
      readRelevantFiles: { path: source?.sourceRef ?? 'PASTE_SELECTED_PATH', startLine: source?.startLine ?? 1, endLine: Math.min(source?.endLine ?? 10, (source?.startLine ?? 1) + 20) },
      createTest: { path: 'src/test/java/com/bugtrace/fixtures/checkout_timeout/GeneratedRetryTest.java', source: 'package com.bugtrace.fixtures.checkout_timeout;\nclass GeneratedRetryTest {}\n' },
      requestExperiment: { operation: 'RUN_SELECTED_TEST', testSelector: 'CheckoutIdempotencyTest', timeoutSeconds: 120 },
      readExecutionResults: { executionId: execution }, readTestReports: { executionId: execution },
      readDiagnosis: {}, proposePatch: { diff: 'PASTE_A_UNIFIED_DIFF_FOR_SELECTED_CURRENT_FILES' },
      requestApprovedChecks: { operation: 'RUN_SELECTED_TEST', testSelector: 'CheckoutIdempotencyTest', timeoutSeconds: 120 },
      createVerificationSummary: { executionIds: [execution] },
    }
    setTool(name); setArgumentsText(JSON.stringify(choices[name], null, 2)); setRequestId(crypto.randomUUID()); setResult(null)
  }
  async function action(kind: 'send' | 'audit' | 'permission' | 'deny') {
    setBusy(true); setError('')
    try {
      if (kind === 'permission') {
        const permission = await api<{ executionToolsEnabled: boolean }>('/execution-permission', { enabled: !enabled })
        setEnabled(permission.executionToolsEnabled)
      } else if (kind === 'send' || kind === 'deny') {
        const body = kind === 'deny'
          ? { requestId: crypto.randomUUID(), expectedRole: role, tool: 'proposePatch', arguments: { diff: 'denial demonstration; no patch should be stored' } }
          : { requestId, expectedRole: role, tool, arguments: JSON.parse(argumentsText) as unknown }
        setResult(await api<Audit>('/tools', body))
      }
      setAudit(await api<Audit[]>('/tools'))
    } catch (ex) { setError(ex instanceof Error ? ex.message : 'Tool request failed') }
    finally { setBusy(false) }
  }
  return <section>
    <h3>Tools for the current role</h3>
    <p>The backend determines the role from saved run state. Only tools on its allowlist can run. Tool results and repository text remain untrusted.</p>
    <p>Execution tools: {enabled ? 'ENABLED for existing baseline checks' : 'DISABLED'}. Limit: three jobs and 30 tool requests per run. Drafts are never installed.</p>
    <button disabled={busy || !['READY', 'TOOL_REVIEW'].includes(state)} onClick={() => void action('permission')}>{enabled ? 'Disable execution tools' : 'Enable execution tools for this trusted fixture run'}</button>
    <p>Select a tool, then use Load example arguments. Replace any explanatory placeholders before sending.</p>
    <label>Tool<select value={tool} disabled={busy || !['READY', 'TOOL_REVIEW'].includes(state)} onChange={event => template(event.target.value)}>{names.map(name => <option key={name}>{name}</option>)}</select></label>
    <button disabled={busy || !['READY', 'TOOL_REVIEW'].includes(state)} onClick={() => template(tool)}>Load example arguments</button>
    <label>Tool arguments JSON<textarea rows={8} value={argumentsText} onChange={event => setArgumentsText(event.target.value)} /></label>
    <p>Tool request UUID: <code>{requestId}</code></p>
    <button disabled={busy || !['READY', 'TOOL_REVIEW'].includes(state)} onClick={() => void action('send')}>Send validated tool request</button>{' '}
    <button disabled={busy} onClick={() => { setRequestId(crypto.randomUUID()); setResult(null) }}>New tool request ID</button>{' '}
    <button disabled={busy} onClick={() => void action('audit')}>Load tool audit</button>{' '}
    {role === 0 && <button disabled={busy || !['READY', 'TOOL_REVIEW'].includes(state)} onClick={() => void action('deny')}>Test denied patch tool</button>}
    <p>Repeating the same UUID and arguments returns its stored result. Changed arguments require a new UUID. Changing roles creates a fresh tool form.</p>
    {error && <p role="alert">{error}</p>}
    {result && <div><h4>{result.tool}: {result.state}</h4>{result.message && <p>{result.message}</p>}{result.execution_id && <p><a href={`#/executions/${result.execution_id}`}>Open queued execution</a></p>}<pre>{JSON.stringify(result.result ?? {}, null, 2)}</pre></div>}
    <details><summary>Tool audit ({audit.length})</summary>{audit.map(item => <div key={item.id}><p>{item.role} · {item.tool} · {item.state} · {item.id}</p>{item.message && <p>{item.message}</p>}{item.execution_id && <a href={`#/executions/${item.execution_id}`}>Execution</a>}<pre>{JSON.stringify(item.result ?? {}, null, 2)}</pre></div>)}</details>
  </section>
}
