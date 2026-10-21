import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'
const source=await readFile(new URL('../src/benchmarkClient.ts',import.meta.url),'utf8')
const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2023,module:ts.ModuleKind.ESNext}}).outputText
const client=await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'))
const investigation='11111111-1111-4111-8111-111111111111',run='22222222-2222-4222-8222-222222222222',snapshot='33333333-3333-4333-8333-333333333333'
const detail={investigationId:investigation,snapshotId:snapshot,verification:{contentHash:'a'.repeat(64)}}
const saved={id:run,investigation_id:investigation,snapshot_id:snapshot,content_hash:'a'.repeat(64)}
test('run URLs require both owned IDs and reject filesystem input',()=>{
 assert.equal(client.runPath(investigation,run),`/investigations/${investigation}/benchmark-reproduction/${run}`)
 for(const path of ['../bbh','C:/Users/ADMIN/bbv','case-001'])assert.throws(()=>client.runPath(path,run))
 assert.throws(()=>client.runPath(investigation,'../private'))
})
test('run load rejects mismatched case, snapshot and content hash',()=>{
 assert.equal(client.checkRun(saved,detail,run),saved)
 for(const key of ['id','investigation_id','snapshot_id','content_hash'])assert.throws(()=>client.checkRun({...saved,[key]:'different'},detail,run))
})
test('record view refuses another investigation or run',()=>{
 const record={document:{identity:{investigationId:investigation,runId:run}}}
 assert.equal(client.checkRecord(record,investigation,run),record)
 assert.throws(()=>client.checkRecord(record,snapshot,run));assert.throws(()=>client.checkRecord(record,investigation,snapshot))
})
test('lost response retry keeps its mutation UUID',()=>{
 const values=new Map();const store={getItem:key=>values.get(key),setItem:(key,value)=>values.set(key,value)}
 const first=client.pendingId(store,'step');assert.equal(client.pendingId(store,'step'),first)
 assert.notEqual(client.pendingId(store,'other-step'),first)
})
test('invalid persisted UUID cannot become a request path',()=>{
 const values=new Map([['step','../bbh']]);const store={getItem:key=>values.get(key),setItem:(key,value)=>values.set(key,value)}
 assert.equal(client.uuid(client.pendingId(store,'step')),true)
})
test('loading uses GET and mutations require an explicit POST body',async t=>{
 const prior=globalThis.fetch;t.after(()=>{globalThis.fetch=prior})
 const seen=[];globalThis.fetch=async(url,options)=>{seen.push({url,options});return{ok:true,text:async()=>'{"saved":true}'}}
 await client.request(client.runPath(investigation,run));await client.request(client.runPath(investigation,run)+'/advance',{requestId:run})
 assert.equal(seen[0].options.method,'GET');assert.equal(seen[1].options.method,'POST')
 assert.equal(seen[1].url,`/api/investigations/${investigation}/benchmark-reproduction/${run}/advance`)
})
test('HTTP errors are surfaced without automatic mutation retries',async t=>{
 const prior=globalThis.fetch;t.after(()=>{globalThis.fetch=prior});let calls=0
 globalThis.fetch=async()=>{calls++;return{ok:false,status:409,text:async()=> 'Budget exhausted'}}
 await assert.rejects(()=>client.request('/benchmark-browser/investigations',{}),/409.*Budget exhausted/)
 assert.equal(calls,1)
})
