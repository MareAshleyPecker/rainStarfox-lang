c11_final_inherit.rsf:4:1: error[checker]: 类 'D' 不能继承 'B'：'B' 是 final 的（DESIGN-27）。rsf 的类默认可以被继承，只有写了 'final' 才禁止——把父类那行的 'final' 去掉才能继承：
  class B(...) { ... }
