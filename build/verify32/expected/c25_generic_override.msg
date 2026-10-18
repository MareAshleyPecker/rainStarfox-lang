c25_generic_override.rsf:5:12: error[checker]: 类 'D' 的 'id' 和父类 'B' 的 id(T) 同签名了，但没写 override。rsf 不许遮蔽（DESIGN-25 §5）——要覆盖它就写成 'override fn id(...)'，只是重名就换个名字
