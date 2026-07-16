// Package service / book.go — Task 3 (text-reader) placeholder.
//
// The full BookService (mtime cache + singleflight + GetBook method) is
// implemented in Task 7. Until then, Task 3 only needs the *BookService type
// to exist so the handler.New signature can reference it as the 6th parameter
// and callers can pass nil. Task 7 will replace this stub wholesale; the
// package-level NewBookService constructor and GetBook method are NOT defined
// here because they require bookparser.Book which does not exist until Task 4.
//
// Keeping the stub in its own file (rather than appending to scanner.go or
// tags.go) makes Task 7's diff clean: it edits only book.go.
package service

// BookService parses and caches books. Task 3 ships only the type shell so
// handler.New can take a *BookService slot; Task 7 fills in the fields and
// methods.
type BookService struct {
	// TODO(Task 7): mu sync.RWMutex, cache map[string]*bookparser.Book, sf singleflight.Group
}
