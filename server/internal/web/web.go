package web

import "embed"

// Assets embeds the Web Manager frontend files.
//go:embed index.html css/*.css css/views/*.css *.js fonts/*.woff2
var Assets embed.FS
