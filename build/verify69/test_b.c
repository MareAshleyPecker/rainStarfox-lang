#include "rsfRuntimelib.h"

int rsf_test_other_tu(void) {
    /* 不定义 RSF_RUNTIME_IMPL 的 TU 也必须看到同一份上下文 */
    rsf_set_err(rsf_str_c("from b"));
    return rsf_err ? 1 : 0;
}
