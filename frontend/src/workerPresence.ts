export type WorkerPresence = {
  credentialId: string; projectId: string; workerId: string; snapshotId: string;
  registeredContentHash: string | null; reportedContentHash: string | null;
  lastContactAt: string | null; serverTime: string;
  readiness: 'BASE_VERIFIED' | 'NOT_READY' | null; currentExecutionId: string | null;
  credentialRevoked: boolean; credentialExpiresAt: string;
  availability: 'NEVER_SEEN' | 'IDLE' | 'BUSY' | 'NOT_READY' | 'OFFLINE' | 'EXPIRED' | 'REVOKED';
}
const statuses = new Set(['NEVER_SEEN', 'IDLE', 'BUSY', 'NOT_READY', 'OFFLINE', 'EXPIRED', 'REVOKED'])
export function readPresence(value: unknown, project: string): WorkerPresence[] {
  if (!Array.isArray(value)) throw new Error('Invalid worker status response')
  return value.map((row: WorkerPresence) => {
    if (!row || row.projectId !== project || !statuses.has(row.availability) || typeof row.workerId !== 'string' || typeof row.credentialId !== 'string' || typeof row.snapshotId !== 'string' || typeof row.credentialRevoked !== 'boolean' || typeof row.serverTime !== 'string') throw new Error('Worker status does not match the signed-in project')
    return row
  })
}
export function snapshotWorkers(rows: WorkerPresence[], snapshot?: string): WorkerPresence[] {
  return snapshot ? rows.filter(row => row.snapshotId === snapshot) : rows
}
export function idleCandidates(rows: WorkerPresence[], snapshot: string, expectedHash?: string): WorkerPresence[] {
  return rows.filter(row => row.snapshotId === snapshot && row.availability === 'IDLE' && row.readiness === 'BASE_VERIFIED' && !row.credentialRevoked && Boolean(row.registeredContentHash) && row.registeredContentHash === row.reportedContentHash && (!expectedHash || row.registeredContentHash === expectedHash))
}
export function workerLabel(value: WorkerPresence['availability']): string {
  return ({ NEVER_SEEN: 'Never connected', IDLE: 'Idle', BUSY: 'Busy', NOT_READY: 'Not ready', OFFLINE: 'Offline', EXPIRED: 'Expired', REVOKED: 'Revoked' })[value]
}
export function queueExplanation(state: string): string {
  if (state === 'QUEUED') return 'Queued — execution has not started. The job waits for an eligible worker to claim it; queueing is not a successful test result.'
  if (state === 'RUNNING') return 'A worker has claimed this job. Inspect its progress and saved execution evidence for the actual result.'
  return 'Inspect the saved result and execution evidence. Worker availability alone does not establish a test outcome.'
}
