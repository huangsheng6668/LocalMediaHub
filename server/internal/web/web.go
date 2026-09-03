package web

import "embed"

// Assets embeds the Web Manager frontend files.
//go:embed index.html css/*.css css/views/*.css *.js fonts/*.woff2 vendor/hls.min.js
var Assets embed.FS
