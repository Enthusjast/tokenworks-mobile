package relay

import (
	"crypto/rand"
	"regexp"
)

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func randomUUID() (string, error) {
	buffer := make([]byte, 16)
	if _, err := rand.Read(buffer); err != nil {
		return "", err
	}
	buffer[6] = buffer[6]&0x0f | 0x40
	buffer[8] = buffer[8]&0x3f | 0x80
	const hex = "0123456789abcdef"
	out := make([]byte, 36)
	at := 0
	for index, value := range buffer {
		if index == 4 || index == 6 || index == 8 || index == 10 {
			out[at] = '-'
			at++
		}
		out[at] = hex[value>>4]
		out[at+1] = hex[value&0x0f]
		at += 2
	}
	return string(out), nil
}
