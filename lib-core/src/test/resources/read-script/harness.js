// 读脚本前置脚本的测试台:用一套极简的假 DOM(只有测试用到的接口)装上前置脚本,逐条跑用例,把结果按 JSON 打印出来。
// 用法:node harness.js <前置脚本> <用例 JSON>;用例为 [{name, code, args?, resultChars?, page?}]。
'use strict';
const fs = require('fs');
const prelude = fs.readFileSync(process.argv[2], 'utf8');
const cases = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const write = process.stdout.write.bind(process.stdout);
const stringify = JSON.stringify;
// 浏览器原生的查找方法把选择器变成字符串时不经页面里的全局 String,假 DOM 也先存一份
const asString = String;

const log = { mutations: 0, clicks: 0, navigations: 0, pageCode: 0, requests: [] };

// 假 DOM 的一套接口。同源 iframe 里的节点在浏览器里属于那个框架自己的一套接口(instanceof 认不出主框架的类,方法也是另一份),
// 所以做成函数:主文档和 iframe 里的文档各用一套
const makeDom = () => {
  class EventTarget {}
  class Node extends EventTarget {
    constructor() { super(); this._kids = []; this._attrs = {}; this.parentNode = null; this._text = null; }
    // 和浏览器一样只有 Node 上一个 getter,对哪种节点都给对的值
    get nodeType() { return this._nodeType; }
    get parentNode() { return this._parent; }
    set parentNode(p) { this._parent = p; }
    // 元素的 _text 是它唯一的子文本节点(textarea 里的字就在这里;_cdata 时是 CDATA 段)。字换了就换上一个新节点,旧的脱离文档、字留着
    get childNodes() { return new NodeList(this._text && this._nodeType === 1 ? [this._textNode()] : this._kids); }
    get firstChild() { return this.childNodes[0] || null; }
    _textNode() {
      if (!this._tn || this._tn._data !== this._text) { if (this._tn) this._tn.parentNode = null; this._tn = this._cdata ? new CDATASection(this) : new Text(this); }
      return this._tn;
    }
    get textContent() { return this._nodeType === 3 || this._nodeType === 4 ? this.data : this._text != null ? this._text : this._kids.map((k) => k.textContent).join(''); }
    set textContent(v) { log.mutations++; this._text = String(v); this._kids = []; }
    appendChild(c) { log.mutations++; this._kids.push(c); c.parentNode = this; return c; }
    removeChild(c) { log.mutations++; this._kids = this._kids.filter((k) => k !== c); return c; }
    contains(o) { return o === this || this._kids.some((k) => k.contains(o)); }
    hasChildNodes() { return this.childNodes.length > 0; }
    _all() { const out = []; for (const k of this._kids) { out.push(k); out.push(...k._all()); } return out; }
    _match(sel) { return this._all().filter((e) => e._is(sel)); }
  }
  // 和浏览器一样,查找方法在 Element、Document、DocumentFragment 上,不在 Node 上;选择器参数和浏览器一样先变成字符串
  const finders = {
    querySelectorAll(sel) { return new NodeList(this._match(asString(sel))); },
    querySelector(sel) { return this._match(asString(sel))[0] || null; },
  };
  class Element extends Node {
    constructor(tag, attrs, text) { super(); this._nodeType = 1; this.tagName = tag.toUpperCase(); Object.assign(this._attrs, attrs || {}); if (text != null) this._text = text; }
    get localName() { return this.tagName.toLowerCase(); }
    get shadowRoot() { return this._shadow || null; }
    _is(sel) {
      for (const part of sel.split(',').map((s) => s.trim())) {
        const m = /^([a-z*]+)?(?:\[([a-z-]+)(?:([\^]?=)([^\]]*))?\])?$/.exec(part);
        if (!m) throw new Error('bad selector ' + part);
        if (m[1] && m[1] !== '*' && m[1] !== this.tagName.toLowerCase()) continue;
        if (m[2] && !(m[2] in this._attrs)) continue;
        return true;
      }
      return false;
    }
    getAttribute(n) { return n in this._attrs ? this._attrs[n] : null; }
    setAttribute(n, v) { log.mutations++; this._attrs[n] = String(v); }
    hasAttribute(n) { return n in this._attrs; }
    closest(sel) { for (let n = this; n && n._nodeType === 1; n = n.parentNode) if (n._is(asString(sel))) return n; return null; }
    // 同一个属性一直是同一个属性节点(和浏览器一样)
    _attrNode(n) { const m = this._attrNodes || (this._attrNodes = {}); return m[n] || (m[n] = new Attr(this, n)); }
    click() { log.clicks++; }
    focus() { log.mutations++; }
    // 在页面文档里复制:和浏览器一样经构造器(自定义元素会跑页面代码)
    cloneNode(deep) {
      const C = this.constructor;
      const c = new C(this.tagName.toLowerCase(), Object.assign({}, this._attrs), this._text);
      if (deep) for (const k of this._kids) { const kc = k.cloneNode(true); c._kids.push(kc); kc.parentNode = c; }
      return c;
    }
    // 复制进不属于任何窗口的空文档:不经构造器
    _copy(deep) {
      const c = Object.create(Object.getPrototypeOf(this));
      Object.assign(c, { _kids: [], _attrs: Object.assign({}, this._attrs), parentNode: null, _text: this._text, _nodeType: 1, tagName: this.tagName });
      if (deep) for (const k of this._kids) { const kc = k._copy(true); c._kids.push(kc); kc.parentNode = c; }
      return c;
    }
    get children() { return new NodeList(this._kids); }
    get attributes() { return new NamedNodeMap(this); }
    get innerHTML() { return this._text != null ? this._text : this._kids.map((k) => k.outerHTML).join(''); }
    set innerHTML(v) { log.mutations++; this._text = String(v); }
    get outerHTML() {
      const t = this.tagName.toLowerCase();
      const a = Object.keys(this._attrs).map((k) => ' ' + k + '="' + this._attrs[k] + '"').join('');
      return '<' + t + a + '>' + this.innerHTML + '</' + t + '>';
    }
  }
  Object.assign(Element.prototype, finders);
  class HTMLElement extends Element {
    // 不排版时浏览器给的就是子孙的文字;排版出来的(_shown)给的是写出来的字
    get innerText() { return this._shown != null ? this._shown : this.textContent; }
    get outerText() { return this.innerText; }
  }
  // 下面几个属性也按子孙的文字算
  class HTMLAnchorElement extends HTMLElement {
    get text() { return this.textContent; }
  }
  class HTMLOptionElement extends HTMLElement {
    get text() { return this.textContent.replace(/\s+/g, ' ').trim(); }
    get label() { return 'label' in this._attrs ? this._attrs.label : this.text; }
    get value() { return 'value' in this._attrs ? this._attrs.value : this.text; }
  }
  class HTMLOutputElement extends HTMLElement {
    get value() { return this.textContent; }
    get defaultValue() { return this.textContent; }
  }
  // 选中第几项(_selected)是页面此刻的状态,复制时不带过去
  class HTMLSelectElement extends HTMLElement {
    get options() { return new NodeList(this._match('option')); }
    get selectedIndex() { return this._selected != null ? this._selected : this.options.length ? 0 : -1; }
    get value() { const o = this.options[this.selectedIndex]; return o ? o.value : ''; }
  }
  class HTMLInputElement extends HTMLElement {
    get type() { return this._attrs.type || 'text'; }
    get value() { return this._value != null ? this._value : (this._attrs.value || ''); }
    set value(v) { log.mutations++; this._value = String(v); }
    get name() { return this._attrs.name || ''; }
    get valueAsNumber() { return this.type === 'number' ? Number(this.value) : NaN; }
    get selectionStart() { return this.value.length; }
    get selectionEnd() { return this.value.length; }
  }
  class HTMLTextAreaElement extends HTMLElement {
    get value() { return this._value != null ? this._value : this.textContent; }
    set value(v) { log.mutations++; this._value = String(v); }
    get name() { return this._attrs.name || ''; }
    get textLength() { return this.value.length; }
  }
  class HTMLIFrameElement extends HTMLElement {
    get contentDocument() { return this._doc || null; }
  }
  // template 的子节点在 content(一个 DocumentFragment)里:querySelectorAll 找不进去,innerHTML 照样写出来;深复制时一起复制
  class HTMLTemplateElement extends HTMLElement {
    constructor(...a) { super(...a); this._content = new DocumentFragment(); }
    get content() { return this._content; }
    get innerHTML() { return this._content._kids.map((k) => k.outerHTML).join(''); }
    cloneNode(deep) {
      const c = super.cloneNode(deep);
      if (deep) for (const k of this._content._kids) { const kc = k.cloneNode(true); c._content._kids.push(kc); kc.parentNode = c._content; }
      return c;
    }
    _copy(deep) {
      const c = super._copy(deep);
      c._content = new DocumentFragment();
      if (deep) for (const k of this._content._kids) { const kc = k._copy(true); c._content._kids.push(kc); kc.parentNode = c._content; }
      return c;
    }
  }
  // 和浏览器一样 substringData 在 CharacterData 上
  class CharacterData extends Node {
    get data() { return this._data; }
    get nodeValue() { return this.data; }
    get length() { return this.data.length; }
    substringData(o, n) { return this.data.substr(o, n); }
  }
  class Text extends CharacterData {
    constructor(owner) { super(); this._nodeType = 3; this.parentNode = owner; this._data = owner._text; }
    get wholeText() { return this.data; }
  }
  // XML 页面里的 CDATA 段:也是文本节点
  class CDATASection extends Text {
    constructor(owner) { super(owner); this._nodeType = 4; }
  }
  // 属性删掉以后属性节点脱离元素:ownerElement 为 null,值留在节点上(_kept)
  class Attr extends Node {
    constructor(owner, name) { super(); this._nodeType = 2; this._owner = owner; this._name = name; }
    get name() { return this._name; }
    get value() { return this._owner ? this._owner._attrs[this._name] : this._kept; }
    get textContent() { return this.value; }
    get nodeValue() { return this.value; }
    get ownerElement() { return this._owner; }
  }
  class NamedNodeMap {
    constructor(el) { const names = Object.keys(el._attrs); names.forEach((n, i) => { this[i] = el._attrNode(n); }); this.length = names.length; this._el = el; }
    item(i) { return this[i] || null; }
    getNamedItem(n) { return n in this._el._attrs ? this._el._attrNode(n) : null; }
  }
  NamedNodeMap.prototype[Symbol.iterator] = Array.prototype.values;
  class NodeList {
    constructor(a) { a.forEach((x, i) => { this[i] = x; }); this.length = a.length; }
    item(i) { return this[i] || null; }
  }
  NodeList.prototype[Symbol.iterator] = Array.prototype.values;
  NodeList.prototype.forEach = Array.prototype.forEach;
  NodeList.prototype.values = Array.prototype.values;
  class DocumentFragment extends Node {
    constructor() { super(); this._nodeType = 11; }
  }
  Object.assign(DocumentFragment.prototype, finders);
  class ShadowRoot extends DocumentFragment {
    constructor(host) { super(); this._host = host; }
    get host() { return this._host; }
    get innerHTML() { return this._kids.map((k) => k.outerHTML).join(''); }
  }
  class Document extends Node {
    constructor() { super(); this._nodeType = 9; this._title = 'Shop'; }
    get title() { return this._title; }
    set title(v) { log.mutations++; this._title = v; }
    get body() { return this._kids[0]; }
    get cookie() { return 'sid=secret-cookie'; }
    get implementation() { return { createHTMLDocument: () => new Document() }; }
    createElement(t) { return new HTMLElement(t); }
    importNode(n, deep) { return n._copy(deep); }
  }
  Object.assign(Document.prototype, finders);
  return { EventTarget, Node, Element, HTMLElement, HTMLAnchorElement, HTMLOptionElement, HTMLOutputElement, HTMLSelectElement, HTMLInputElement, HTMLTextAreaElement, HTMLIFrameElement, HTMLTemplateElement, CharacterData, Text, Attr, NamedNodeMap, NodeList, DocumentFragment, ShadowRoot, Document };
};
const { EventTarget, Node, Element, HTMLElement, HTMLAnchorElement, HTMLOptionElement, HTMLOutputElement, HTMLSelectElement, HTMLInputElement, HTMLTextAreaElement, HTMLIFrameElement, HTMLTemplateElement, CharacterData, Text, Attr, NamedNodeMap, NodeList, DocumentFragment, ShadowRoot, Document } = makeDom();
// 页面自己定义的自定义元素:构造时跑页面代码
class XCard extends HTMLElement { constructor(...a) { super(...a); log.pageCode++; } }

const doc = new Document();
const body = new HTMLElement('body');
doc._kids.push(body);
const add = (parent, el) => { parent._kids.push(el); el.parentNode = parent; return el; };
const list = add(body, new HTMLElement('ul'));
add(list, new HTMLElement('li', { 'data-id': '1' }, 'apple'));
add(list, new HTMLElement('li', { 'data-id': '2' }, 'pear'));
const form = add(body, new HTMLElement('form'));
const pw = add(form, new HTMLInputElement('input', { type: 'password', value: 'hunter2' }));
add(form, new HTMLInputElement('input', { type: 'hidden', name: 'csrf', value: 'tok-123' }));
const query = add(form, new HTMLInputElement('input', { type: 'text', name: 'q', value: 'shoes' }));
add(form, new HTMLInputElement('input', { type: 'number', name: 'otp', autocomplete: 'one-time-code', value: '482913' }));
add(form, new HTMLTextAreaElement('textarea', { name: 'code', autocomplete: 'one-time-code' }, 'otp-in-textarea'));
const note = add(form, new HTMLTextAreaElement('textarea', { name: 'note' }, 'leave at door'));
add(form, new XCard('x-card', {}, 'card'));
// template 里的隐藏字段:不在文档树上,innerHTML 照样写出来
add(add(form, new HTMLTemplateElement('template'))._content, new HTMLInputElement('input', { type: 'hidden', name: 'tpl', value: 'tpl-secret' }));
// 同源 iframe:里面的文档用另一套接口;密码框里是用户打的字(值,不是属性);它是 XML 页面,textarea 里的字是 CDATA 段
const frame = makeDom();
const frameDoc = new frame.Document();
const frameBody = new frame.HTMLElement('body');
frameDoc._kids.push(frameBody);
const frameForm = add(frameBody, new frame.HTMLElement('form'));
add(frameForm, new frame.HTMLInputElement('input', { type: 'password', name: 'password' }))._value = 'typed-in-frame';
add(frameForm, new frame.HTMLInputElement('input', { type: 'hidden', name: 'csrf', value: 'tok-frame' }));
add(frameForm, new frame.HTMLInputElement('input', { type: 'text', name: 'q', value: 'frame-q' }));
add(frameForm, new frame.HTMLTextAreaElement('textarea', { autocomplete: 'one-time-code' }, 'frame-otp'))._cdata = true;
add(body, new HTMLIFrameElement('iframe'))._doc = frameDoc;
// 开放的 shadow root:里面的节点不在文档树上
const host = add(body, new HTMLElement('div'));
host._shadow = new ShadowRoot(host);
add(host._shadow, new HTMLInputElement('input', { type: 'password', value: 'shadow-default' }));
add(host._shadow, new HTMLInputElement('input', { type: 'hidden', value: 'shadow-token' }));
const shadowCode = add(host._shadow, new HTMLTextAreaElement('textarea', { autocomplete: 'one-time-code' }, 'shadow-otp'));
// SVG 里也有叫 template 的元素:它没有 content,子节点就在文档树上
add(add(add(body, new Element('svg')), new Element('template')), new Element('circle'));
// 链接、选项里的秘密 textarea(页面脚本放进去的):a、option 的 text 一类按子孙的文字算;页面选中的是第二项
const link = add(body, new HTMLAnchorElement('a'));
add(link, new HTMLElement('span', {}, 'Link '));
add(link, new HTMLTextAreaElement('textarea', { autocomplete: 'one-time-code' }, 'otp-in-link'));
const select = add(body, new HTMLSelectElement('select'));
add(select, new HTMLOptionElement('option', {}, 'One'));
const option = add(select, new HTMLOptionElement('option'));
add(option, new HTMLElement('span', {}, 'Card '));
add(option, new HTMLTextAreaElement('textarea', { autocomplete: 'cc-number' }, '4111-in-option'));
select._selected = 1;
// 排版出来的元素:innerText 是写出来的字,不含 textarea 里的字;选项除外,它按自己的 text 写出来
select._shown = 'One\nCard 4111-in-option';
const shown = add(body, new HTMLElement('p'));
add(shown, new HTMLElement('span', {}, 'Code: '));
add(shown, new HTMLTextAreaElement('textarea', { autocomplete: 'one-time-code' }, 'otp-in-p'));
shown._shown = 'Code: ';
// option 带 value 属性时 value 是属性;output 的 value、defaultValue 按子孙的文字算
const select2 = add(body, new HTMLSelectElement('select'));
const valued = add(select2, new HTMLOptionElement('option', { value: 'v-attr' }));
add(valued, new HTMLElement('span', {}, 'Pick '));
add(valued, new HTMLTextAreaElement('textarea', { autocomplete: 'one-time-code' }, 'otp-in-valued'));
const output = add(body, new HTMLOutputElement('output'));
add(output, new HTMLElement('span', {}, 'Out '));
add(output, new HTMLTextAreaElement('textarea', { autocomplete: 'one-time-code' }, 'otp-in-output'));

// 用例的 page:跑之前页面自己做的事(浏览器里是页面脚本),返回跑完后怎么还原
const pageActions = {
  // 「显示密码」:读取世界建起之后把密码框改成 text
  showPassword: () => { pw._attrs.type = 'text'; return () => { pw._attrs.type = 'password'; }; },
  // 把打进去的字写回 textarea 的初始值:换上一个新的文本节点,旧的脱离文档
  typeBack: () => { const old = shadowCode._text; shadowCode._text = 'otp-typed'; shadowCode._textNode(); return () => { shadowCode._text = old; shadowCode._textNode(); }; },
  // 删掉密码框的 value 属性
  dropValue: () => {
    const a = pw._attrNode('value');
    a._kept = pw._attrs.value; a._owner = null; delete pw._attrs.value; delete pw._attrNodes.value;
    return () => { pw._attrs.value = a._kept; };
  },
  // 页面取消选中:selectedIndex 为 -1
  unselect: () => { select._selected = -1; return () => { select._selected = 1; }; },
  // 框后来才变成秘密:加上 autocomplete、改成密码框,把字写进模型拿着的那个节点,再换掉字、删掉 value 属性(节点脱离文档)
  lateSecret: () => {
    const n = note._textNode(), a = query._attrNode('value'), text = note._text, value = query._attrs.value;
    note._attrs.autocomplete = 'one-time-code'; n._data = 'late-otp'; note._text = 'later'; note._textNode();
    query._attrs.type = 'password'; query._attrs.value = 'late-pw';
    a._kept = query._attrs.value; a._owner = null; delete query._attrs.value; delete query._attrNodes.value;
    return () => { delete note._attrs.autocomplete; note._text = text; note._textNode(); query._attrs.type = 'text'; query._attrs.value = value; };
  },
};
// 页面上的节点此刻的样子(属性、值、文字,含 template 的内容、shadow root、iframe 里的文档):读完和读之前一比,就知道原件有没有被改过
const pageState = () => {
  const out = [];
  const walk = (n) => {
    out.push([n.tagName || n._nodeType, n._attrs, n._value, n._text]);
    for (const k of n._kids) walk(k);
    for (const x of [n._content, n._shadow, n._doc]) if (x) walk(x);
  };
  walk(doc);
  return stringify(out);
};

class Location {
  constructor() { this.href = 'https://shop.test/list'; this.origin = 'https://shop.test'; }
  assign() { log.navigations++; }
  replace() { log.navigations++; }
}
const location = new Location();
Object.defineProperty(location, 'toString', { value: function () { return this.href; } });
Object.defineProperty(location, 'valueOf', { value: function () { return this; } });

// 会发请求的页面接口:真的被调到就记一笔
class XMLHttpRequest { constructor() { log.requests.push({ via: 'xhr' }); } open(m, u) { log.requests.push({ via: 'xhr', method: m, url: u }); } send() {} }
class WebSocket { constructor(u) { log.requests.push({ via: 'ws', url: u }); } }
class EventSource { constructor(u) { log.requests.push({ via: 'sse', url: u }); } }
class Image { constructor() { log.requests.push({ via: 'img' }); } }
const navigator = { userAgent: 'test', sendBeacon(u) { log.requests.push({ via: 'beacon', url: u }); return true; } };

Object.assign(globalThis, {
  XMLHttpRequest, WebSocket, EventSource, Image,
  Node, Element, HTMLElement, HTMLInputElement, HTMLTextAreaElement, HTMLIFrameElement, HTMLTemplateElement, CharacterData, Text, Attr, NamedNodeMap, NodeList, DocumentFragment, ShadowRoot, Document, EventTarget,
  document: doc, location,
  getComputedStyle() { return { display: 'block' }; },
  open() { log.navigations++; },
  fetch: async (url, opts) => {
    log.requests.push({ url, method: opts && opts.method, credentials: opts && opts.credentials, redirect: opts && opts.redirect });
    // 不跟随跳转时浏览器给的是 opaqueredirect 响应
    if (url.endsWith('/moved')) return { type: 'opaqueredirect', status: 0, ok: false, url: '', body: null, headers: new Headers() };
    return new Response(stringify({ items: [1, 2], token: 'abc' }), { headers: { 'content-type': 'application/json' } });
  },
});

// Node 自带的 navigator 只有 getter:换成假的
Object.defineProperty(globalThis, 'navigator', { value: navigator, configurable: true, writable: true });

(0, eval)(prelude);
const run = globalThis.__mikaReadRun;

(async () => {
  const out = {};
  for (const c of cases) {
    const before = { mutations: log.mutations, clicks: log.clicks, navigations: log.navigations, pageCode: log.pageCode, requests: log.requests.length };
    const lim = { resultChars: c.resultChars || 5000, sleepMs: 50 };
    const undo = c.page ? pageActions[c.page]() : null;
    const state = pageState();
    let r;
    try { r = await run(c.code, c.args == null ? null : c.args, lim); } catch (e) { r = { ok: false, error: 'harness', message: String(e) }; }
    const pageChanged = pageState() !== state;
    if (undo) undo();
    out[c.name] = Object.assign({}, r, { pageChanged,
      mutations: log.mutations - before.mutations, clicks: log.clicks - before.clicks,
      navigations: log.navigations - before.navigations, pageCode: log.pageCode - before.pageCode, requests: log.requests.slice(before.requests),
    });
  }
  write(stringify(out));
})();
