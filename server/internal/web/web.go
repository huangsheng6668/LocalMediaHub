package web

import "embed"

// Assets embeds the Web Manager frontend files.
//go:embed index.html style.css *.js
var Assets embed.FS
