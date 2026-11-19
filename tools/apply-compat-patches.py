#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给 app/src/main/assets/chat-demo/index.html 打兼容补丁（幂等，可重复执行）。

chat-demo 的产物按现代 Chromium（108+）编译，而部分旧手机（如 Redmi Note 8 Pro，
Android System WebView 87）缺少以下能力，会导致白屏 / 发送消息后整页空白：

1. dvh 单位（Chrome 108）：手机布局写的是 height: var(--app-viewport-height, 100dvh)，
   变量未设置且 dvh 不支持时整条 height 失效 -> 容器高度 0 -> 白屏。
2. Array.prototype.at（Chrome 92）：流式响应组装消息时大量使用 arr.at(-1)。
3. Object.hasOwn（Chrome 93）、structuredClone（Chrome 98）、
   Array.prototype.toSorted/toReversed/toSpliced/with、findLast/findLastIndex。

用法：python tools/apply-compat-patches.py
"""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX = os.path.join(ROOT, "app", "src", "main", "assets", "chat-demo", "index.html")

BEGIN = "<!--compat:begin-->"
END = "<!--compat:end-->"

VIEWPORT_CSS = (
    "<style>@supports not (height:100dvh){:root{--app-viewport-height:100vh}}</style>"
)

POLYFILL_JS = """<script>
(function(){
  var def=function(o,k,v){try{Object.defineProperty(o,k,{value:v,writable:true,configurable:true})}catch(e){o[k]=v}};
  if(!Array.prototype.at)def(Array.prototype,'at',function(n){var k=Math.trunc(Number(n))||0,l=this.length;if(k<0)k+=l;return k<0||k>=l?void 0:this[k]});
  if(!String.prototype.at)def(String.prototype,'at',function(n){var s=String(this),k=Math.trunc(Number(n))||0,l=s.length;if(k<0)k+=l;return k<0||k>=l?void 0:s.charAt(k)});
  if(!Object.hasOwn)def(Object,'hasOwn',function(o,k){return Object.prototype.hasOwnProperty.call(Object(o),k)});
  if(!Array.prototype.findLast)def(Array.prototype,'findLast',function(f,t){for(var i=this.length-1;i>=0;i--)if(f.call(t,this[i],i,this))return this[i]});
  if(!Array.prototype.findLastIndex)def(Array.prototype,'findLastIndex',function(f,t){for(var i=this.length-1;i>=0;i--)if(f.call(t,this[i],i,this))return i;return -1});
  if(!Array.prototype.toReversed)def(Array.prototype,'toReversed',function(){return this.slice().reverse()});
  if(!Array.prototype.toSorted)def(Array.prototype,'toSorted',function(c){return this.slice().sort(c)});
  if(!Array.prototype.toSpliced)def(Array.prototype,'toSpliced',function(){var a=this.slice();a.splice.apply(a,arguments);return a});
  if(!Array.prototype.with)def(Array.prototype,'with',function(i,v){var a=this.slice(),k=Math.trunc(Number(i))||0;if(k<0)k+=a.length;if(k<0||k>=a.length)throw new RangeError('Invalid index');a[k]=v;return a});
  if(typeof window.structuredClone!=='function'){
    window.structuredClone=function structuredClone(value){
      var seen=new WeakMap();
      function clone(v){
        if(v===null||typeof v!=='object')return v;
        if(seen.has(v))return seen.get(v);
        var tag=Object.prototype.toString.call(v);
        if(tag==='[object Date]')return new Date(v.getTime());
        if(tag==='[object RegExp]')return new RegExp(v.source,v.flags);
        if(tag==='[object ArrayBuffer]')return v.slice(0);
        if(tag==='[object DataView]')return new DataView(v.buffer.slice(0),v.byteOffset,v.byteLength);
        if(ArrayBuffer.isView(v))return new v.constructor(v);
        if(tag==='[object Blob]'||tag==='[object File]'||tag==='[object FileList]'||tag==='[object ImageData]')return v;
        if(tag==='[object Map]'){var m=new Map();seen.set(v,m);v.forEach(function(val,key){m.set(clone(key),clone(val))});return m}
        if(tag==='[object Set]'){var st=new Set();seen.set(v,st);v.forEach(function(val){st.add(clone(val))});return st}
        if(Array.isArray(v)){var arr=[];seen.set(v,arr);for(var i=0;i<v.length;i++)arr[i]=clone(v[i]);return arr}
        var out={};seen.set(v,out);Object.keys(v).forEach(function(k){out[k]=clone(v[k])});return out
      }
      return clone(value);
    };
  }
})();
</script>"""

PATCH = BEGIN + VIEWPORT_CSS + POLYFILL_JS + END


def main():
    html = io.open(INDEX, encoding="utf-8").read()

    if BEGIN in html and END in html:
        head, rest = html.split(BEGIN, 1)
        _, tail = rest.split(END, 1)
        html = head + tail

    if "<script defer" not in html:
        raise SystemExit("找不到主脚本标签，index.html 结构可能变了")

    html = html.replace("<script defer", PATCH + "<script defer", 1)
    io.open(INDEX, "w", encoding="utf-8", newline="").write(html)
    print("patched: %s (%d bytes)" % (INDEX, len(html.encode("utf-8"))))


if __name__ == "__main__":
    main()
