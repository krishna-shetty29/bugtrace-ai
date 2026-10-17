import { useEffect, useState } from 'react'

type Spec = { testSource: string; intendedAssertion: string; requiredFaultScenario: string; evidenceIds: string[]; expectedFailureSignature: string; explanation: string }
type Attempt = { attempt_number: number; state: string; call_id: string; execution_id: string | null; source_sha: string | null; document: Spec | null; result: Record<string, unknown> | null }
type Reproduction = { id: string; state: string; max_repairs: number; attempts_count: number; message: string | null; path: string; selector: string; attempts: Attempt[] }
export default function GeneratedReproductionPanel({ investigationId, runId, role, runState }: { investigationId: string; runId: string; role: number; runState: string }) {
  const [requestId] = useState(() => crypto.randomUUID())
  const [data, setData] = useState<Reproduction | null>(null)
  const [maxRepairs, setMaxRepairs] = useState(2)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const prefix = `/api/investigations/${investigationId}/agent-runs/${runId}/reproduction`
  useEffect(() => {
    if (data?.state !== 'QUEUED') return
    const controller = new AbortController()
    const timer = window.setInterval(() => {
      void fetch(prefix, { signal: controller.signal }).then(async response => {
        if (!response.ok) throw new Error(`Reproduction refresh HTTP ${response.status}`)
        setData(await response.json() as Reproduction)
      }).catch(ex => { if (!controller.signal.aborted) setError(ex instanceof Error ? ex.message : 'Refresh failed') })
    }, 3000)
    return () => { window.clearInterval(timer); controller.abort() }
  }, [prefix, data?.state])
  async function action(kind: 'load' | 'generate' | 'execute' | 'repair' | 'abandon') {
    setBusy(true); setError('')
    try {
      const attempt = data?.attempts.at(-1)?.attempt_number ?? -1
      const body = kind === 'execute' ? { expectedAttempt: attempt, timeoutSeconds: 300 }
        : kind === 'abandon' ? {} : { requestId, maxRepairs: data?.max_repairs ?? maxRepairs, expectedAttempt: attempt }
      const response = await fetch(kind === 'load' ? prefix : `${prefix}/${kind}`, {
        method: kind === 'load' ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' },
        body: kind === 'load' ? undefined : JSON.stringify(body),
      })
      const text = await response.text()
      if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1500)}`)
      setData(JSON.parse(text) as Reproduction)
    } catch (ex) { setError(ex instanceof Error ? ex.message : 'Reproduction request failed') }
    finally { setBusy(false) }
  }
  const active = role === 1 && runState === 'READY'
  const latest = data?.attempts.at(-1)
  return <section>
    <h3>Generate a reproduction test</h3>
    <p>Use while this run is READY at REPRODUCTION_AGENT. Generation consumes the parent model budget. Source is written only by the worker into a new experiment copy.</p>
    <p>Start a fresh run with eight calls and 12000 output tokens under the default 1500-token per-call limit to cover all roles plus initial generation and two repairs.</p>
    <label>Maximum compiler repairs<input type="number" min={0} max={2} value={maxRepairs} disabled={busy || data !== null} onChange={event => setMaxRepairs(Number(event.target.value))} /></label>
    <button disabled={busy || !active || data !== null} onClick={() => void action('generate')}>Generate test proposal</button>{' '}
    <button disabled={busy} onClick={() => void action('load')}>Load saved reproduction</button>
    {error && <p role="alert">{error}</p>}
    {data && <div><h4>{data.state}</h4><p>Proposal UUID: {data.id}. Attempts: {data.attempts_count}; maximum repairs: {data.max_repairs}.</p>{data.message && <p>{data.message}</p>}
      <p>Experiment-only path: <code>{data.path}</code></p>
      {latest?.document && <div><p><strong>Intended assertion:</strong> {latest.document.intendedAssertion}</p><p><strong>Required fault:</strong> {latest.document.requiredFaultScenario}</p><p><strong>Expected failure:</strong> {latest.document.expectedFailureSignature}</p><p>Evidence IDs: {latest.document.evidenceIds.join(', ')}</p><p>{latest.document.explanation}</p><details open><summary>Generated test source — review before execution</summary><pre>{latest.document.testSource}</pre></details><p>Source SHA-256: <code>{latest.source_sha}</code></p></div>}
      <p>Enable execution tools in the role tool panel and start one worker for this snapshot before running. The worker performs test-compile, then the exact generated test.</p>
      <button disabled={busy || !active || data.state !== 'DRAFT_READY'} onClick={() => void action('execute')}>Compile and run generated test</button>{' '}
      <button disabled={busy || !active || data.state !== 'COMPILE_ERROR'} onClick={() => void action('repair')}>Repair generated-file compilation error</button>{' '}
      <button disabled={busy || !['DRAFT_READY', 'COMPILE_ERROR'].includes(data.state)} onClick={() => void action('abandon')}>Stop this generated test</button>
      <p>Only a compiler error naming this generated file permits a repair. Passing tests, assertion failures, timeouts and infrastructure errors do not trigger repairs. No automatic model retry occurs.</p>
      {data.state === 'QUEUED' && <p>Polling saved execution results every three seconds. Do not queue another copy.</p>}
      {data.attempts.map(attempt => <details key={attempt.attempt_number}><summary>Attempt {attempt.attempt_number + 1}: {attempt.state}</summary><p>Saved model call: {attempt.call_id}</p>{attempt.execution_id && <p><a href={`#/executions/${attempt.execution_id}`} target="_blank" rel="noreferrer">Open execution logs and test reports</a></p>}<pre>{JSON.stringify(attempt.result, null, 2)}</pre><details><summary>Saved proposal version</summary><pre>{JSON.stringify(attempt.document, null, 2)}</pre></details></details>)}
      <p>EXPECTED_FAILURE_OBSERVED records the expected generated-test signature. Review source and actual reports; it is not a verified diagnosis or fix.</p>
    </div>}
  </section>
}
