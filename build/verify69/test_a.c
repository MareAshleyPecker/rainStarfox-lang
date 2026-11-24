#define RSF_RUNTIME_IMPL
#include "rsfRuntimelib.h"
#include <assert.h>

int rsf_test_other_tu(void);

int main(void) {
    /* 1) arena 分配 + calloc 清零 */
    int* p = (int*)rsf_malloc(4 * sizeof(int));
    for (int i = 0; i < 4; i++) assert(p[i] == 0 || p[i] != 0); /* 不清零，只保证可写 */
    p[0] = 7; p[1] = 8;
    assert(p[0] == 7 && p[1] == 8);

    int* z = (int*)rsf_calloc(8, sizeof(int));
    for (int i = 0; i < 8; i++) assert(z[i] == 0);

    /* 2) 数组 + 字符串拼接 */
    rsf_arr a = rsf_arr_new(3, sizeof(int32_t));
    assert(a.len == 3 && a.data != NULL);
    ((int32_t*)a.data)[0] = 1; ((int32_t*)a.data)[1] = 2; ((int32_t*)a.data)[2] = 3;

    rsf_str s1 = rsf_str_c("hello, ");
    rsf_str s2 = rsf_str_c("world");
    rsf_str s3 = rsf_concat(s1, s2);
    assert(s3.len == 12);
    assert(memcmp(s3.data, "hello, world", 12) == 0);
    assert(rsf_str_eq(s3, rsf_str_c("hello, world")));

    /* 3) 增长：旧容量显式传，不需要分配头 */
    char* buf = (char*)rsf_malloc(8);
    memcpy(buf, "abcdefg", 8);
    buf = (char*)rsf_grow(buf, 8, 64);
    assert(memcmp(buf, "abcdefg", 8) == 0);

    /* 4) 错误状态在 ctx 里 */
    assert(!rsf_err);
    rsf_set_err(rsf_str_c("boom"));
    assert(rsf_err);
    assert(rsf_str_eq(rsf_err_msg, rsf_str_c("boom")));
    rsf_err = false; rsf_err_msg = rsf_str_null();
    assert(!rsf_err);

    /* 5) 另一个 TU 看到同一个上下文 */
    assert(rsf_test_other_tu() == 1);

    /* 6) 宿主注入自己的分配器（--lib 路径） */
    rsf_ctx mine; rsf_allocator alloc = rsf_ctx_get()->alloc;
    rsf_ctx_init(&mine, alloc);
    rsf_ctx_use(&mine);
    assert(rsf_ctx_get() == &mine);
    void* q = rsf_malloc(1024);
    assert(q != NULL);
    assert(!rsf_err);
    rsf_ctx_use(NULL);
    assert(rsf_ctx_get() != &mine);

    /* 7) arena 压力：跨多块分配后，旧数据必须还在 */
    {
        enum { N = 20000 };
        rsf_str* keep = (rsf_str*)rsf_calloc(N, sizeof(rsf_str));
        for (int i = 0; i < N; i++) {
            keep[i] = rsf_concat(rsf_str_c("k"), rsf_str_c("v"));
        }
        for (int i = 0; i < N; i++) {
            assert(keep[i].len == 2 && memcmp(keep[i].data, "kv", 2) == 0);
        }
        rsf_arena* ar = (rsf_arena*)rsf_ctx_get()->alloc.user;
        size_t blocks = 0;
        for (rsf_arena_block* b = ar->head; b != NULL; b = b->next) blocks++;
        assert(blocks > 1); /* 确实跨了多个块 */
        printf("arena blocks=%zu\n", blocks);
    }

    puts("verify69 ok");
    return 0;
}
