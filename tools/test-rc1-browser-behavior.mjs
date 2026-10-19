import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const fixture=await browserFixture();
try {
  // DSH 0.2.1 replaced the styled claim-token leaf with CommandChip nodes; the Lexical
  // claim-decoration guard was retired with it, so only the materialized-node contract remains.
  await fixture.load('dsh-client-ui-conversation', ['conversation-materialized-patch.json'].map(name=>JSON.parse(fs.readFileSync('app/src/main/assets/'+name,'utf8'))),
    'exports.audit={ConversationNodeAssembler};');
  const materialized=await fixture.page.evaluate(()=>{
    const {ConversationNodeAssembler}=auditExports['@deepseek-ai/dsh-client-ui-conversation'].audit;
    const assembler=Object.create(ConversationNodeAssembler.prototype);
    const context={current:new Map()};let next={key:'stable',target:'messages',visibility:'visible',text:'retained'};
    assembler.buildNode=()=>next;
    const visible=assembler.buildTargetUpserts('messages',[context])[0];next=null;
    const hidden=assembler.buildTargetUpserts('messages',[context])[0];
    const repeated=assembler.buildTargetUpserts('messages',[context])[0];
    next={...visible,text:'new'};const restored=assembler.buildTargetUpserts('messages',[context])[0];
    const reset=assembler.buildTargetNodes('messages',[]);
    return {visible,hidden,repeatedSame:repeated===hidden,restored,reset};
  });
  assert.equal(materialized.hidden.key,materialized.visible.key);assert.equal(materialized.hidden.visibility,'hidden');
  assert.equal(materialized.hidden.text,'retained');assert.equal(materialized.repeatedSame,true);
  assert.equal(materialized.restored.visibility,'visible');assert.equal(materialized.restored.text,'new');assert.deepEqual(materialized.reset,[]);
  assert.deepEqual(fixture.errors,[]);
  console.log(JSON.stringify({status:'PASS',engine:await fixture.browser.version(),materialized},null,2));
}finally{await fixture.close();}
