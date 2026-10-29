package main

import (
	"bufio"
	"compress/gzip"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

const packMagic = "ADPK"
const packVersion = 1

var errNotArchive = errors.New("не контейнер ADPK")

func isPackFile(name string) bool {
	return filepath.Ext(name) == ".adp"
}

type countReader struct {
	r io.Reader
	n int64
}

func (c *countReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n += int64(n)
	return n, err
}

func unpackArchive(path, dir string) ([]FileInfo, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	br := bufio.NewReaderSize(f, 1<<20)

	magic := make([]byte, len(packMagic))
	if _, err := io.ReadFull(br, magic); err != nil {
		return nil, err
	}
	if string(magic) != packMagic {
		return nil, errNotArchive
	}
	version, err := br.ReadByte()
	if err != nil {
		return nil, err
	}
	if version != packVersion {
		return nil, fmt.Errorf("версия контейнера %d не поддерживается", version)
	}
	if _, err := io.CopyN(io.Discard, br, 3); err != nil {
		return nil, err
	}

	var out []FileInfo
	for {
		nameLen, err := readUint16(br)
		if err == io.EOF {
			break
		}
		if err != nil {
			return out, err
		}
		if nameLen == 0 || nameLen > 4096 {
			return out, fmt.Errorf("некорректное имя записи (%d)", nameLen)
		}
		nameBytes := make([]byte, nameLen)
		if _, err := io.ReadFull(br, nameBytes); err != nil {
			return out, err
		}
		name := sanitizeName(string(nameBytes))

		dataLen, err := readUint64(br)
		if err != nil {
			return out, err
		}
		if dataLen == 0 {
			continue
		}

		dst := filepath.Join(dir, name)
		of, err := os.Create(dst)
		if err != nil {
			return out, err
		}
		cr := &countReader{r: io.LimitReader(br, int64(dataLen))}
		zr, err := gzip.NewReader(cr)
		if err != nil {
			of.Close()
			return out, err
		}
		written, err := io.Copy(of, zr)
		zr.Close()
		of.Close()
		if err != nil {
			return out, err
		}
		if remaining := int64(dataLen) - cr.n; remaining > 0 {
			if _, err := io.CopyN(io.Discard, br, remaining); err != nil {
				return out, err
			}
		}
		out = append(out, FileInfo{Name: name, Path: dst, Size: written})
	}
	return out, nil
}

func readUint16(r io.Reader) (int, error) {
	var b [2]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return int(binary.BigEndian.Uint16(b[:])), nil
}

func readUint64(r io.Reader) (uint64, error) {
	var b [8]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint64(b[:]), nil
}
