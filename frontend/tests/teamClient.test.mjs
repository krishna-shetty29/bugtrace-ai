import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'
const source=await readFile(new URL('../src/teamClient.ts',import.meta.url),'utf8')
const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2023,module:ts.ModuleKind.ESNext}}).outputText
const {teamRequest}=await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'))
const origin='http://127.0.0.1:5173'
test('mutation adds CSRF while preserving JSON and custom headers',()=>{const result=teamRequest('/api/investigations',{method:'POST',headers:{'Content-Type':'application/json','X-Custom':'kept'},body:'{}'},origin,'token');assert.equal(result.headers.get('X-BugTrace-CSRF'),'token');assert.equal(result.headers.get('Content-Type'),'application/json');assert.equal(result.headers.get('X-Custom'),'kept');assert.equal(result.body,'{}')})
test('foreign origins and non-API paths never receive CSRF',()=>{for(const path of ['https://foreign.invalid/api/x','/assets/x'])assert.equal(teamRequest(path,{method:'POST'},origin,'token').headers,undefined)})
test('reads and absent session do not add CSRF',()=>{for(const method of ['GET','HEAD','OPTIONS'])assert.equal(teamRequest('/api/x',{method},origin,'token').headers,undefined);assert.equal(teamRequest('/api/x',{method:'POST'},origin,'').headers,undefined)})
test('Request object method and headers remain effective',()=>{const request=new Request(origin+'/api/x',{method:'DELETE',headers:{'X-Custom':'value'}});const result=teamRequest(request,undefined,origin,'token');assert.equal(result.headers.get('X-BugTrace-CSRF'),'token');assert.equal(result.headers.get('X-Custom'),'value')})
