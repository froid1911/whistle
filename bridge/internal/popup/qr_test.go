package popup

import (
	"image"
	"image/color"
	"strings"
	"testing"

	"github.com/charmbracelet/lipgloss"
	"github.com/makiuchi-d/gozxing"
	gzqr "github.com/makiuchi-d/gozxing/qrcode"
	"github.com/skip2/go-qrcode"

	"github.com/froid1911/whistle/bridge/internal/devauth"
	"github.com/froid1911/whistle/bridge/internal/server"
)

var testURI = devauth.PairURI("100.64.0.10:8787", "YYHQ7CZP3HHWAWWQO72E5EIULY", "MkR9tVZeCyn4s6dBt2aCjTQQfhPzyXnLrICFTJuIP-g")

func TestChooseLayout(t *testing.T) {
	n := qrSize(testURI)
	t.Logf("modules incl. quiet zone: %d, uri len %d", n, len(testURI))
	for _, c := range []struct {
		w, h int
		want layout
	}{
		{160, 50, layout{qrHalf, true}},
		{120, 35, layout{qrHalf, true}},
		{100, 30, layout{qrHalf, false}},
		{80, 24, layout{qrHalf, false}},
		{50, 20, layout{kind: qrNone}},
		{30, 26, layout{qrQuad, false}},
	} {
		if got := chooseLayout(c.w, c.h, n, len(testURI)); got != c.want {
			t.Errorf("%dx%d: got %+v want %+v", c.w, c.h, got, c.want)
		}
	}
}

func TestPairScreenFits(t *testing.T) {
	for _, s := range [][2]int{{160, 50}, {120, 35}, {100, 30}, {80, 24}, {50, 20}} {
		tok := fakeTok()
		out := pairScreen(tok, s[0], s[1], "4:59")
		if lipgloss.Height(out) > s[1] || lipgloss.Width(out) > s[0] {
			t.Errorf("%v: screen is %dx%d", s, lipgloss.Width(out), lipgloss.Height(out))
		}
		if !strings.Contains(out, tok.Code) {
			t.Errorf("%v: no code", s)
		}
	}
	if out := pairScreen(fakeTok(), 50, 20, "1:00"); !strings.Contains(out, "Enlarge the window") || !strings.Contains(out, "whistle://") {
		t.Errorf("fallback text missing:\n%s", out)
	}
}

// raster turns block glyphs back into a bitmap (glyph set = light module),
// cw x ch modules per cell, with a white border so the decoder is happy.
func raster(lines []string, quad bool) image.Image {
	const scale, pad = 4, 8
	cw, ch := 1, 2
	if quad {
		cw = 2
	}
	w := len([]rune(lines[0])) * cw
	h := len(lines) * ch
	img := image.NewGray(image.Rect(0, 0, (w+2*pad)*scale, (h+2*pad)*scale))
	for i := range img.Pix {
		img.Pix[i] = 255
	}
	for r, line := range lines {
		for c, g := range []rune(line) {
			var mask int
			switch g {
			case ' ':
			case '█':
				mask = 15
			case '▀':
				mask = 3
			case '▄':
				mask = 12
			default:
				for m, q := range quadGlyph {
					if q == g {
						mask = m
					}
				}
			}
			for i := 0; i < 4; i++ {
				dx, dy := i%2, i/2
				if !quad && dx == 1 {
					continue
				}
				if mask&(1<<i) != 0 {
					continue // light stays white
				}
				for y := 0; y < scale; y++ {
					for x := 0; x < scale; x++ {
						img.SetGray(((c*cw+dx)+pad)*scale+x, ((r*ch+dy)+pad)*scale+y, color.Gray{0})
					}
				}
			}
		}
	}
	return img
}

func TestRenderDecodes(t *testing.T) {
	for _, quad := range []bool{false, true} {
		lines := renderQR(testURI, quad, false)
		bmp, err := gozxing.NewBinaryBitmapFromImage(raster(lines, quad))
		if err != nil {
			t.Fatal(err)
		}
		res, err := gzqr.NewQRCodeReader().Decode(bmp, nil)
		if err != nil {
			t.Fatalf("quad=%v: %v\n%s", quad, err, strings.Join(lines, "\n"))
		}
		if res.GetText() != testURI {
			t.Errorf("quad=%v: decoded %q", quad, res.GetText())
		}
	}
}

func TestShortURISmallerThanOld(t *testing.T) {
	old := "whistle://pair?v=1&u=100.64.0.10%3A8787&t=YYHQ7CZP3HHWAWWQO72E5EIULY&f=MkR9tVZeCyn4s6dBt2aCjTQQfhPzyXnLrICFTJuIP-g&n=myserver"
	q, _ := qrcode.New(old, qrcode.Medium)
	q.DisableBorder = true
	t.Logf("before %d modules (+8 quiet), after %d (+4)", len(q.Bitmap()), qrSize(testURI)-4)
}

func fakeTok() server.PairingToken {
	return server.PairingToken{Token: "YYHQ7CZP3HHWAWWQO72E5EIULY", Code: "YYHQ-7CZP-3HHW-AWWQ-O72E-5EIU-LY",
		URI: testURI, Addr: "100.64.0.10:8787", Name: "myserver"}
}

func TestDownloadHint(t *testing.T) {
	for _, c := range []struct {
		w, h     int
		line, qr bool
	}{
		{160, 50, true, true},
		{120, 35, true, true},
		{70, 40, true, false}, // full layout, no room for a second QR
		{100, 30, false, false},
		{50, 20, true, false}, // text fallback
	} {
		out := pairScreen(fakeTok(), c.w, c.h, "4:59")
		if got := strings.Contains(out, "No Whistle yet?"); got != c.line {
			t.Errorf("%dx%d: download line %v, want %v", c.w, c.h, got, c.line)
		}
		if got := strings.Contains(out, "Scan to download"); got != c.qr {
			t.Errorf("%dx%d: download QR %v, want %v", c.w, c.h, got, c.qr)
		}
		if lipgloss.Height(out) > c.h || lipgloss.Width(out) > c.w {
			t.Errorf("%dx%d: screen is %dx%d", c.w, c.h, lipgloss.Width(out), lipgloss.Height(out))
		}
	}
	if !strings.Contains(pairScreen(fakeTok(), 160, 50, "4:59"), "github.com/froid1911/whistle/releases/latest") {
		t.Error("download URL text missing")
	}
}

func TestDownloadQRDecodes(t *testing.T) {
	lines := renderQR(downloadURL, false, false)
	bmp, err := gozxing.NewBinaryBitmapFromImage(raster(lines, false))
	if err != nil {
		t.Fatal(err)
	}
	res, err := gzqr.NewQRCodeReader().Decode(bmp, nil)
	if err != nil {
		t.Fatal(err)
	}
	if res.GetText() != downloadURL {
		t.Errorf("decoded %q", res.GetText())
	}
}
