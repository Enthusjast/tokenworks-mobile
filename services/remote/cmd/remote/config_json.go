package main

import (
	"encoding/json"
	"reflect"
	"strings"
)

// encoding/json matches struct names case-insensitively. Policy files instead
// use their literal JSON tags at every typed object/array level. Custom JSON
// decoders retain their own representation contracts (for example time.Time).
func exactConfigFieldNames(raw []byte, target any) bool {
	var value any
	if json.Unmarshal(raw, &value) != nil {
		return false
	}
	unmarshaler := reflect.TypeFor[json.Unmarshaler]()
	fields := map[reflect.Type]map[string]reflect.Type{}
	var names func(reflect.Type) map[string]reflect.Type
	names = func(kind reflect.Type) map[string]reflect.Type {
		if known, ok := fields[kind]; ok {
			return known
		}
		result := map[string]reflect.Type{}
		fields[kind] = result
		for index := 0; index < kind.NumField(); index++ {
			field := kind.Field(index)
			if field.PkgPath != "" {
				continue
			}
			name := strings.Split(field.Tag.Get("json"), ",")[0]
			if name == "-" {
				continue
			}
			base := field.Type
			for base.Kind() == reflect.Pointer {
				base = base.Elem()
			}
			if field.Anonymous && name == "" && base.Kind() == reflect.Struct {
				for key, value := range names(base) {
					result[key] = value
				}
				continue
			}
			if name == "" {
				name = field.Name
			}
			result[name] = field.Type
		}
		return result
	}
	var walk func(any, reflect.Type) bool
	walk = func(value any, kind reflect.Type) bool {
		if kind == nil {
			return false
		}
		if value == nil {
			return true
		}
		for kind.Kind() == reflect.Pointer {
			if kind.Implements(unmarshaler) {
				return true
			}
			kind = kind.Elem()
		}
		if kind.Implements(unmarshaler) || reflect.PointerTo(kind).Implements(unmarshaler) {
			return true
		}
		switch typed := value.(type) {
		case map[string]any:
			switch kind.Kind() {
			case reflect.Struct:
				allowed := names(kind)
				for key, child := range typed {
					field, ok := allowed[key]
					if !ok || !walk(child, field) {
						return false
					}
				}
			case reflect.Map:
				for _, child := range typed {
					if !walk(child, kind.Elem()) {
						return false
					}
				}
			}
		case []any:
			if kind.Kind() == reflect.Slice || kind.Kind() == reflect.Array {
				for _, child := range typed {
					if !walk(child, kind.Elem()) {
						return false
					}
				}
			}
		}
		return true
	}
	return walk(value, reflect.TypeOf(target))
}
