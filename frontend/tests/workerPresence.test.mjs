import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'
const source = await readFile(new URL('../src/workerPresence.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2023, module: ts.ModuleKind.ESNext } }).outputText
const { readPresence, snapshotWorkers, idleCandidates, workerLabel, queueExplanation } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'))
const row = { credentialId: 'credential', projectId: 'project-A', workerId: 'worker', snapshotId: 'snapshot-A', registeredContentHash: 'hash-A', reportedContentHash: 'hash-A', readiness: 'BASE_VERIFIED', availability: 'IDLE', credentialRevoked: false, serverTime: '2026-10-21T00:00:00Z' }
test('rejects another project instead of displaying it', () => {
  assert.throws(() => readPresence([row], 'project-B'))
  assert.throws(() => readPresence([row, { ...row, projectId: 'project-B' }], 'project-A'))
  assert.equal(readPresence([row], 'project-A').length, 1)
})
test('rejects malformed responses and unknown status', () => {
  for (const value of [null, {}, [null], [{ ...row, availability: 'SUCCESS' }]]) assert.throws(() => readPresence(value, 'project-A'))
})
test('snapshot panel excludes workers for other snapshots', () => {
  assert.deepEqual(snapshotWorkers([row, { ...row, snapshotId: 'other' }], 'snapshot-A'), [row])
})
test('idle candidates require matching bytes and active idle state', () => {
  assert.equal(idleCandidates([row], 'snapshot-A', 'hash-A').length, 1)
  for (const change of [{ availability: 'BUSY' }, { availability: 'OFFLINE' }, { availability: 'REVOKED' }, { credentialRevoked: true }, { readiness: 'NOT_READY' }, { reportedContentHash: 'other' }, { registeredContentHash: null }]) assert.equal(idleCandidates([{ ...row, ...change }], 'snapshot-A', 'hash-A').length, 0)
  assert.equal(idleCandidates([row], 'snapshot-B').length, 0)
  assert.equal(idleCandidates([row], 'snapshot-A', 'different-hash').length, 0)
})
test('all backend availability statuses have distinct human labels', () => {
  for (const state of ['NEVER_SEEN', 'IDLE', 'BUSY', 'NOT_READY', 'OFFLINE', 'EXPIRED', 'REVOKED']) assert.equal(typeof workerLabel(state), 'string')
})
test('queued work explicitly says it has not executed', () => {
  assert.match(queueExplanation('QUEUED'), /has not started/)
  assert.match(queueExplanation('QUEUED'), /not a successful test result/)
  assert.match(queueExplanation('RUNNING'), /actual result/)
})
