# 保留崩溃栈行号：R8 默认会剥掉 SourceFile/LineNumberTable，
# 没有这两项，mapping.txt 反混淆后定位不到行。随包归档 mapping.txt。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
