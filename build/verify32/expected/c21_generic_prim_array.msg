c21_generic_prim_array.rsf:6:18: error[checker]: 泛型参数 'T' 绑到了基本类型 'int'：'T[]' 在 JVM 上擦成 Object[]，装不下基本类型数组（DESIGN-31 §8）——把实参换成引用元素数组（例如 string[] 或类数组）
