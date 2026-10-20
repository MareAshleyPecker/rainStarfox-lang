// rainStarfox UI 宿主（Win32，零依赖）
//
// 由 `--c --lib` 生成的 C 提供 rsf 函数，这个文件只做三件事：
//   开窗 → 把鼠标/键盘/文字喂给 App → 把 App 的像素贴到窗口上。
//
// 编译：
//   rsf build examples/ui_app.rsf --c --lib -o build/host
//   gcc host/win32_main.c build/host/ui_app.c -o build/ui_app.exe
//
// 用法：
//   ui_app.exe                开窗（Esc 退出）
//   ui_app.exe --frames 2     无窗口跑两帧，打印动作数后退出（判据用）
//   ui_app.exe --shot a.bmp   无窗口渲染一帧，存 BMP
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

// 与生成 C 里的运行时结构必须逐字段一致。
typedef struct {
    int64_t len;
    char* data;
} rsf_str;

typedef struct {
    int64_t len;
    void* data;
} rsf_arr;

// rsf 侧 App 的 ABI（`rsfSrc/ui/Host.rsf`）。
void* rsf_fn_app_new(int32_t w, int32_t h);
int32_t rsf_fn_app_frame(void* app);
int32_t rsf_fn_app_action(void* app, int32_t i);
int32_t rsf_fn_app_width(void* app);
int32_t rsf_fn_app_height(void* app);
rsf_arr rsf_fn_app_pixels(void* app);
void rsf_fn_app_move(void* app, int32_t x, int32_t y);
void rsf_fn_app_down(void* app, int32_t x, int32_t y);
void rsf_fn_app_up(void* app);
void rsf_fn_app_text(void* app, rsf_str s);
void rsf_fn_app_backspace(void* app);

#define SCALE 2

static void* g_app = NULL;
static int32_t g_canvas_w = 320;
static int32_t g_canvas_h = 180;

static void write_bmp(const char* path, int32_t w, int32_t h, const int32_t* px) {
    int row = (w * 3 + 3) & ~3;
    int size = row * h;
    unsigned char header[54];
    memset(header, 0, sizeof(header));
    header[0] = 'B';
    header[1] = 'M';
    int total = 54 + size;
    memcpy(header + 2, &total, 4);
    int off = 54;
    memcpy(header + 10, &off, 4);
    int hs = 40;
    memcpy(header + 14, &hs, 4);
    memcpy(header + 18, &w, 4);
    memcpy(header + 22, &h, 4);
    short planes = 1;
    short bpp = 24;
    memcpy(header + 26, &planes, 2);
    memcpy(header + 28, &bpp, 2);
    memcpy(header + 34, &size, 4);
    FILE* f = fopen(path, "wb");
    if (!f) {
        fprintf(stderr, "cannot write %s\n", path);
        return;
    }
    fwrite(header, 1, 54, f);
    unsigned char* line = (unsigned char*)calloc(1, (size_t)row);
    for (int y = h - 1; y >= 0; y--) {
        for (int x = 0; x < w; x++) {
            int c = px[y * w + x];
            line[x * 3 + 0] = (unsigned char)(c & 0xFF);
            line[x * 3 + 1] = (unsigned char)((c >> 8) & 0xFF);
            line[x * 3 + 2] = (unsigned char)((c >> 16) & 0xFF);
        }
        fwrite(line, 1, (size_t)row, f);
    }
    free(line);
    fclose(f);
}

static int paint(HWND hwnd) {
    rsf_arr pixels = rsf_fn_app_pixels(g_app);
    if (!pixels.data) return 0;
    int32_t w = g_canvas_w;
    int32_t h = g_canvas_h;
    BITMAPINFO bi;
    memset(&bi, 0, sizeof(bi));
    bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth = w;
    bi.bmiHeader.biHeight = -h; // 自上而下
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;

    int32_t* argb = (int32_t*)malloc((size_t)w * (size_t)h * 4);
    if (!argb) return 0;
    int32_t* src = (int32_t*)pixels.data;
    for (int i = 0; i < w * h; i++) {
        int c = src[i];
        argb[i] = ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF);
    }

    HDC dc = GetDC(hwnd);
    RECT rc;
    GetClientRect(hwnd, &rc);
    SetStretchBltMode(dc, COLORONCOLOR);
    StretchDIBits(dc, 0, 0, rc.right - rc.left, rc.bottom - rc.top,
                  0, 0, w, h, argb, &bi, DIB_RGB_COLORS, SRCCOPY);
    ReleaseDC(hwnd, dc);
    free(argb);
    return 1;
}

/// 跑一帧，把动作 id 交给宿主；返回动作数。
static int frame_once(void) {
    int n = rsf_fn_app_frame(g_app);
    for (int i = 0; i < n; i++) {
        printf("[action] %d\n", rsf_fn_app_action(g_app, i));
    }
    fflush(stdout);
    return n;
}

static LRESULT CALLBACK proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT ps;
        BeginPaint(hwnd, &ps);
        paint(hwnd);
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_MOUSEMOVE: {
        int x = (short)LOWORD(lp) / SCALE;
        int y = (short)HIWORD(lp) / SCALE;
        rsf_fn_app_move(g_app, x, y);
        return 0;
    }
    case WM_LBUTTONDOWN: {
        int x = (short)LOWORD(lp) / SCALE;
        int y = (short)HIWORD(lp) / SCALE;
        rsf_fn_app_down(g_app, x, y);
        int n = frame_once();
        for (int i = 0; i < n; i++) {
            if (rsf_fn_app_action(g_app, i) == 2) PostQuitMessage(0);
        }
        InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    }
    case WM_LBUTTONUP:
        rsf_fn_app_up(g_app);
        return 0;
    case WM_CHAR: {
        wchar_t wc = (wchar_t)wp;
        char buf[8];
        int n = WideCharToMultiByte(CP_UTF8, 0, &wc, 1, buf, sizeof(buf), NULL, NULL);
        if (n > 0) {
            rsf_str s;
            s.len = n;
            s.data = buf;
            rsf_fn_app_text(g_app, s);
            frame_once();
            InvalidateRect(hwnd, NULL, FALSE);
        }
        return 0;
    }
    case WM_KEYDOWN:
        if (wp == VK_BACK) {
            rsf_fn_app_backspace(g_app);
            frame_once();
            InvalidateRect(hwnd, NULL, FALSE);
            return 0;
        }
        if (wp == VK_ESCAPE) {
            PostQuitMessage(0);
            return 0;
        }
        return 0;
    }
    return DefWindowProc(hwnd, msg, wp, lp);
}

int main(int argc, char** argv) {
    int frames = -1;
    const char* shot = NULL;
    int click_x = -1;
    int click_y = -1;
    const char* type_text = NULL;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--frames") == 0 && i + 1 < argc) frames = atoi(argv[++i]);
        else if (strcmp(argv[i], "--shot") == 0 && i + 1 < argc) shot = argv[++i];
        else if (strcmp(argv[i], "--click") == 0 && i + 1 < argc) {
            const char* v = argv[++i];
            const char* comma = strchr(v, ',');
            if (comma) {
                click_x = atoi(v);
                click_y = atoi(comma + 1);
            }
        } else if (strcmp(argv[i], "--text") == 0 && i + 1 < argc) type_text = argv[++i];
    }

    g_app = rsf_fn_app_new(g_canvas_w, g_canvas_h);
    if (!g_app) {
        fprintf(stderr, "app_new failed\n");
        return 1;
    }

    if (frames >= 0 || shot != NULL || type_text != NULL || click_x >= 0) {
        int total = 0;
        int count = frames > 0 ? frames : 1;
        frame_once(); // 先摆一次版，命中矩形才有值
        if (click_x >= 0) {
            rsf_fn_app_down(g_app, click_x, click_y);
            rsf_fn_app_up(g_app);
        }
        if (type_text != NULL) {
            rsf_str s;
            s.len = (int64_t)strlen(type_text);
            s.data = (char*)type_text;
            rsf_fn_app_text(g_app, s);
        }
        for (int i = 0; i < count; i++) total += frame_once();
        if (shot != NULL) {
            rsf_arr pixels = rsf_fn_app_pixels(g_app);
            write_bmp(shot, g_canvas_w, g_canvas_h, (const int32_t*)pixels.data);
            printf("[shot] %s\n", shot);
        }
        printf("[frames] %d action(s)=%d\n", count, total);
        return 0;
    }

    WNDCLASSA wc;
    memset(&wc, 0, sizeof(wc));
    wc.lpfnWndProc = proc;
    wc.hInstance = GetModuleHandleA(NULL);
    wc.lpszClassName = "RainStarfoxUi";
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    if (!RegisterClassA(&wc)) {
        fprintf(stderr, "RegisterClass failed\n");
        return 1;
    }
    RECT r = {0, 0, g_canvas_w * SCALE, g_canvas_h * SCALE};
    AdjustWindowRect(&r, WS_OVERLAPPEDWINDOW, FALSE);
    HWND hwnd = CreateWindowA("RainStarfoxUi", "rainStarfox UI", WS_OVERLAPPEDWINDOW,
                              CW_USEDEFAULT, CW_USEDEFAULT, r.right - r.left, r.bottom - r.top,
                              NULL, NULL, wc.hInstance, NULL);
    if (!hwnd) {
        fprintf(stderr, "CreateWindow failed\n");
        return 1;
    }
    ShowWindow(hwnd, SW_SHOW);
    frame_once();
    MSG msg;
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }
    return 0;
}
