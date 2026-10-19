import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import path from 'node:path';
import {testRuntime} from './test-runtime-fixture.mjs';
// Split from test-task-progress.mjs: the checks that need the real locked rc2 dsh-subprocess scrubber.
// The variables are set before scrubbing, so the scrubber itself (not the notifier) must remove them.
const webRegistration={DSH_TASK_GENERATION:'3',DSH_TASK_INSTANCE:'actual-runtime',DSH_TASK_TOKEN:'a'.repeat(64)};
// Import the actual canonical rc2 scrubber used by bash/MCP/PTY launchers.
const raw=testRuntime('raw');
const subprocessSource=path.join(raw,'node_modules/@deepseek-ai/dsh-subprocess/lib/index.js');
assert.equal(JSON.parse(readFileSync(path.join(raw,'node_modules/@deepseek-ai/dsh-subprocess/package.json'),'utf8')).version,'0.2.1-rc.2');
const {scrubbedParentEnv}=await import(pathToFileURL(subprocessSource).href);
for(const [key,value]of Object.entries(webRegistration)) process.env[key]=value;
const filtered=scrubbedParentEnv();
for(const key of Object.keys(webRegistration)) assert.ok(!Object.hasOwn(filtered,key),'canonical rc2 scrubber must remove '+key+' even before notifier initialization');
const moduleUrl=pathToFileURL(path.resolve('app/src/main/assets/builtin-plugins/dsh-task-notifier/lib/index.js')).href;
// Ordinary independent CLI inherits the real scrubbed child env and no process registry.
const child=spawnSync(process.execPath,['--input-type=module','-e',`
import {TaskReporter} from ${JSON.stringify(moduleUrl)};
const session={id:'ordinary-cli',surface:{contentGeneration:0}};
const agent={session,status:'running'};
const ctx={webServer:{port:41821},agents:{get:()=>agent,roots:()=>[agent]},sessionProjections:{stateOf:()=>({lastTurn:1,openTurnStartSeq:1})}};
let calls=0;const reporter=new TaskReporter(ctx,async()=>{calls++;return {}});
reporter.event(session,{type:'turn/start',data:{turn:1}});await reporter.tick();reporter.dispose();
console.log(JSON.stringify({calls,ambient:['DSH_TASK_GENERATION','DSH_TASK_INSTANCE','DSH_TASK_TOKEN'].map(key=>Object.hasOwn(process.env,key))}));
`],{env:scrubbedParentEnv(),encoding:'utf8',timeout:10000});
assert.equal(child.status,0,child.stderr);assert.deepEqual(JSON.parse(child.stdout.trim()),{calls:0,ambient:[false,false,false]});
console.log('task-progress-runtime: real rc2 scrubber removes DSH_TASK_* set before scrubbing, scrubbed independent CLI publishes zero context PASS; subprocess source SHA256='+createHash('sha256').update(readFileSync(subprocessSource)).digest('hex'));
