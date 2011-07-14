java-websocket-client
=====================
This project is intended to provide a Java WebSocket class similar to the standard W3C WebSocket class found
in browsers.  While it is just standard Java, I wrote it for an Android app and have geared it in that
direction.

The client implements draft76 (currently this is what the browsers support) and draft03 (which is newer
but as yet not implemented by mainstream browsers).  I intend to track the draft03 work with how the spec
evolves.

The companion to this project is node-websocket-handler (https://github.com/stellaeof/node-websocket-handler), which
provides WebSocket handling in a node.js environment.

Approach
--------
Unlike a simple protocol abstraction class, this implementation is thread-aware and was built to operate in a GUI
environment where all IO should happen off of the main event thread.  Thread marshalling is all built-in just as
in the W3C WebSocket API it was modeled after.

I ended up doing blocking IO instead of using NIO for a couple of reasons:

* On Android, everything uses the blocking model with worker threads anyway
* Implementing NIO-based SSL is a royal PITA
* For a client, there isn't going to be much saved (if anything) to offset the complexity

Status
------
This project is not yet done and I don't recommend using it just yet.

License
-------
Use of this software is permitted under an MIT-esque license (below).

Copyright (c) 2011 Stella Laurenzo

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
