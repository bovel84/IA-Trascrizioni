#!/usr/bin/env node
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert/strict');
const engine=require('../concept-map.js'),fixture=require('./fixtures/concept-map.json');
const {createEnvironment}=require('./dom-stub');let checks=0;
function check(name,value){assert.ok(value,name);checks++;console.log('ok  '+name);}
function overlaps(a,b){return a.x<b.x+b.w&&a.x+a.w>b.x&&a.y<b.y+b.h&&a.y+a.h>b.y;}
const graph=engine.layout(fixture),original=JSON.stringify(fixture);
check('the screenshot example is a real two-sided branching map',graph.nodes.some(n=>n.side===-1)&&graph.nodes.some(n=>n.side===1)&&new Set(graph.nodes.map(n=>n.x)).size>=4);
check('the explicit central topic is honored even when it is not the first node',graph.roots[0]==='mini');
check('source nodes, uncertainties and all eleven relations are retained',graph.nodes.length===12&&graph.edges.length===11&&JSON.stringify(fixture)===original&&graph.nodes.find(n=>n.id==='fund').uncertain);
check('no concept cards overlap each other',graph.nodes.every((a,i)=>graph.nodes.slice(i+1).every(b=>!overlaps(a,b))));
check('children inherit the color of their actual thematic branch',graph.nodes.find(n=>n.id==='risk').branch===graph.nodes.find(n=>n.id==='reporting').branch&&graph.nodes.find(n=>n.id==='fund').branch!==graph.nodes.find(n=>n.id==='risk').branch);
check('all relationship labels have visible text, not just hover titles',graph.edges.every(e=>e.lines.join(' ')===e.label&&e.labelW>0&&e.labelH>0));
check('long labels wrap without ellipses or losing words',graph.nodes.find(n=>n.id==='fund').lines.join(' ')==='Fondo Innovazione / veicolo Progetto (proposta)'&&!graph.nodes.some(n=>n.lines.join('').includes('…')));
check('nodes and labels fit in the exported full-canvas bounds',graph.nodes.every(n=>n.x>=0&&n.y>=0&&n.x+n.w<=graph.width&&n.y+n.h<=graph.height)&&graph.edges.every(e=>e.ly-e.labelH/2>=0&&e.ly+e.labelH/2<=graph.height));
check('layout is deterministic and independent of a narrow screen',JSON.stringify(engine.layout(fixture))===JSON.stringify(graph));
const cycle={topic:'Ciclo',nodes:[{id:'a',label:'A'},{id:'b',label:'B'},{id:'c',label:'C'},{id:'d',label:'Separato'}],links:[{from:'a',to:'b',label:'avvia'},{from:'b',to:'c',label:'richiede'},{from:'c',to:'a',label:'ritorna a'}]};
const cycled=engine.layout(cycle);
check('cycles and disconnected topics remain visible without invented links',cycled.nodes.length===4&&cycled.edges.length===3&&cycled.edges.some(e=>e.cross)&&cycled.roots.length===2);
const inbound=engine.layout({root:'topic',nodes:[{id:'a',label:'Causa'},{id:'topic',label:'Tema'},{id:'b',label:'Vincolo'}],links:[{from:'a',to:'topic',label:'contribuisce a'},{from:'b',to:'topic',label:'limita'}]});
check('incoming relations keep the real central topic and original arrow direction',inbound.roots.length===1&&inbound.roots[0]==='topic'&&inbound.edges.every(edge=>edge.to==='topic')&&inbound.nodes.some(n=>n.side===-1)&&inbound.nodes.some(n=>n.side===1));
check('cross-link routes reserve separate labeled lanes',cycled.edges.filter(e=>e.cross).every(e=>e.ly-e.labelH/2>=0&&e.ly+e.labelH/2<Math.min(...cycled.nodes.map(n=>n.y))));
check('the alternative level view retains the entire graph',engine.layout(fixture,{mode:'levels'}).edges.length===11&&engine.layout(fixture,{mode:'levels'}).nodes.every(n=>n.side===1));
const levels=engine.layout(fixture,{mode:'levels'});check('thematic colors stay stable when switching views',graph.nodes.every(n=>n.branch===levels.nodes.find(other=>other.id===n.id).branch));
const single=engine.layout({nodes:[{id:'a',label:'Un solo concetto'}],links:[]});check('a single concept remains a valid finite diagram',single.width>0&&single.height>0&&single.nodes.length===1);
check('Unicode labels retain every code point when wrapped',engine.wrap('🙂漢字'.repeat(25),80).join('')==='🙂漢字'.repeat(25));
const html=fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8'),ctx=createEnvironment({html}),run=code=>vm.runInContext(code,ctx);ctx.fixture=fixture;
(async()=>{
 for(const m of html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi))run(m[1]);await run('init()');run('renderConceptMap(fixture)');
 check('legacy and enhanced node details survive normalization',run('conceptMapData.nodes.find(n=>n.id==="fund").kind')==='proposta'&&run('conceptMapData.nodes.find(n=>n.id==="fund").uncertain'));
 run("selectMapNode('fund')");check('selecting a concept opens its full text and connected navigation',!ctx.__elements.get('mapConceptDetail').hidden&&ctx.__elements.get('mapConceptDetail').children.some(el=>el.textContent.includes('Non assegnato'))&&run('conceptViewer.selected')==='fund');
 const zoom=run('conceptViewer.scale');run('zoomMap(1.25)');check('zoom changes the viewport without changing model data',run('conceptViewer.scale')>zoom&&run('conceptMapData.links.length')===11);
 const before=run('JSON.stringify(conceptMapData)');run("$('mapLayoutSelect').value='levels';changeMapLayout()");check('changing layout preserves content and the selected concept',run('JSON.stringify(conceptMapData)')===before&&run('conceptViewer.selected')==='fund');
 run('toggleMapExpanded()');check('the map can open in an app-native expanded view',run('mapExpanded')&&ctx.__elements.get('mapExpandBtn').getAttribute('aria-expanded')==='true');
 await run('newTranscript()');check('opening a new project releases map listeners and full-screen state',!run('mapExpanded')&&run('conceptViewer')===null);
 check('the prompt requires real named relations and a central hierarchy',run('AI_INSTRUCTIONS.concept').includes('2-5 rami')&&run('AI_INSTRUCTIONS.concept').includes('Non inventare nessi'));
 run(`validateConceptEvidence(normalizeConceptMap({nodes:[{id:'x',label:'Budget',evidence:'Budget approvato'}],links:[]}),'Il Budget approvato resta confermato.')`);check('literal map evidence is accepted when present in the source',true);
 assert.throws(()=>run(`validateConceptEvidence(normalizeConceptMap({nodes:[{id:'x',label:'Budget',evidence:'Decisione inventata'}],links:[]}),'Documento originale')`));check('invented node evidence is rejected before replacing a saved map',true);
 assert.throws(()=>run(`normalizeConceptMap({root:'missing',nodes:[{id:'a',label:'A'}],links:[]})`));check('an invalid central node cannot corrupt the graph',true);
 console.log(`${checks} concept-map checks passed.`);
})().then(()=>process.exit(0)).catch(error=>{console.error(error);process.exit(1);});
