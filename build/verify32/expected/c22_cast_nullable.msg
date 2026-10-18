c22_cast_nullable.rsf:6:22: error[parser]: 目标类型不许写可空：'... as ?...' 不合法——'null is A' 恒为 false、'null as A' 抛、'null ?as A' 得 null，行为由「写的是 as 还是 ?as」决定，跟目标带不带 '?' 无关；去掉 '?' 写成 'as ...'
