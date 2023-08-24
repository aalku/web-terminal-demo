# Terminal demo with xterm.js, Spring Boot and pty4j

You can run a console remorely from your web browser, it just works.



- Select a hardcoded COMMAND at src/org/aalku/demo/term/TermController.java
- Execute it (as jar or with maven or whatever).
- Open http://localhost:8080/
- Enjoy

This demo uses fetch for some things and WebSockets for other things, with long-request backup thanks to SockJS for the case WebSockets does not work in your system.

I think it can run any command and interactive terminal applications with ansi colors and animations, like htop or bash. I trust better the backend on Linux than Windows or Mac but it's tested on Windows 11 too, cmd.exe runs fine.

License is MIT or whatever the dependencies require. This is just a PoC with no warranty.