export type EvidenceRef = { kind: string; indexId: string; chunkKey: string | null; chunkNumber: number | null }
export type Evidence = { id: string; reference: EvidenceRef; origin: string; sourceRef: string; startLine: number; endLine: number; sourceHash: string; text: string }
export type Case = { caseId: string; collectionVersion: string; releaseCommit: string; modulePath: string; bugReport: string; expectedBehavior: string }
export type Detail = { investigationId: string; registrationId: string; snapshotId: string; title: string; caseId: string; collectionVersion: string; releaseCommit: string; imported: boolean; verification: { verified: boolean; contentHash: string } | null; bugReport: string; expectedBehavior: string }
export type Settings = { model: { provider: string; model: string; mock: boolean; maxOutputTokens: number }; evidence: Evidence[]; runs: { id: string; state: string }[] }
export type Source = { testSource: string; intendedAssertion: string; requiredFaultScenario: string; evidenceIds: string[]; expectedFailureSignature: string; explanation: string }
export type Run = { experiment_jobs?: number; compiler_repairs?: number; id: string; investigation_id: string; snapshot_id: string; content_hash: string; provider: string; model: string; state: string; evidence_ids: string[]; calls: number; max_calls: number; reserved_output_tokens: number; max_output_tokens: number; message: string | null; plan: { outcome: string; hypothesis: string; evidenceIds: string[]; missingEvidence: string[]; explanation: string } | null; source_document: Source | null; steps: { id: string; call_id: string | null; action: string; state: string; error: string | null }[] }
export type SavedRecord = { recordId: string; documentSha256: string; kind: string; document: { identity: { investigationId: string; runId: string; caseId: string }; finalConclusion?: unknown; generatedSource?: { version: number; sourceSha256: string }; results?: unknown; executionIds?: string[]; executionStatus?: string; unboundExecutionCount?: number } }
export const uuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
export function runPath(investigation: string, run?: string) {
  if (!uuid(investigation) || (run !== undefined && !uuid(run))) throw new Error('Select a saved investigation and run.')
  return `/investigations/${investigation}/benchmark-reproduction${run ? `/${run}` : ''}`
}
export async function request<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api${path}`, { signal, method: body === undefined ? 'GET' : 'POST', headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1500)}`)
  return JSON.parse(text) as T
}
export function checkRun(run: Run, detail: Detail, expectedRun: string) {
  if (run.id !== expectedRun || run.investigation_id !== detail.investigationId || run.snapshot_id !== detail.snapshotId || run.content_hash !== detail.verification?.contentHash) throw new Error('The saved run does not match the verified case. Reload the investigation.')
  return run
}
export function checkRecord(record: SavedRecord, investigation: string, run: string) {
  if (record.document.identity.investigationId !== investigation || record.document.identity.runId !== run) throw new Error('The saved record belongs to another run.')
  return record
}
// Keep a mutation UUID after a lost response. Retrying must not create another model call/run.
export function pendingId(storage: Pick<Storage, 'getItem' | 'setItem'>, key: string) {
  const prior = storage.getItem(key)
  if (prior && uuid(prior)) return prior
  const id = crypto.randomUUID(); storage.setItem(key, id); return id
}
export function download(name: string, text: string, type = 'text/plain') {
  const url = URL.createObjectURL(new Blob([text], { type })); const link = document.createElement('a')
  link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export type Experiment = { executionId: string; queueState: string; sourceVersion: number; sourceSha256: string; observation: string; technicalError: string | null; result: { classification?: string } | null; logs?: { stdout: string | null; stderr: string | null } }
