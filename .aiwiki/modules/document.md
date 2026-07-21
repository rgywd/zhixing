---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：document

MuPDF 文档渲染与解析模块，封装 PDF 操作及多格式转 Markdown 能力。

模块以 MuPDF 的 Java 绑定为核心，`com.artifex.mupdf.fitz` 包提供文档加载、页面渲染、注释、表单、签名和文本提取等底层 API。`me.rerere.document` 包则在此基础上实现 Docx/Epub/Pdf/Pptx 到 Markdown 的解析器。数据流通常从 `Document` 或 `Page` 开始，通过 `Device` 子类绘制到 `Pixmap`，或通过 `StructuredText` 提取结构化文本。对外提供 `Page`、`PDFDocument`、`StructuredText` 等接口，依赖 MuPDF 原生库及 Android 基础库。

修改指引：修改渲染行为从 `Device` 或 `Page` 入手；新增文档格式解析则参考 `DocxParser` 等实现。

## 文件摘要

### `document/build.gradle.kts`

Android 库构建配置，定义插件、SDK 与依赖。
- `plugins`：应用 `android.library` 插件
- `android`：命名空间 `me.rerere.document`，编译 SDK 37，最低 SDK 26，Java 11
- `dependencies`：`core-ktx`、`appcompat`、`material` 等基础库

### `document/consumer-rules.pro`

保留 MuPDF 库的 ProGuard 消费者规则。
- `-keep class com.artifex.mupdf.** {*;}`：保留所有 MuPDF 类防止混淆/移除。

### `document/proguard-rules.pro`

ProGuard 混淆规则文件，当前为空。
- 无自定义规则：文件仅含注释及示例，未定义任何实际混淆配置。

### `document/src/androidTest/java/me/rerere/document/ExampleInstrumentedTest.kt`

Android 仪器测试示例，验证应用上下文包名。
- `ExampleInstrumentedTest`：Android 仪器测试类
- `useAppContext`：测试函数，断言目标包名

### `document/src/main/AndroidManifest.xml`

Android 应用清单文件，当前为空壳，未声明任何组件、权限或配置。
- `<manifest>`：根元素，定义应用包命名空间，暂无实际配置。

### `document/src/main/java/com/artifex/mupdf/fitz/AbortException.java`

MuPDF 操作中止异常类。  
- `AbortException`：继承 RuntimeException，表示操作被中止。

### `document/src/main/java/com/artifex/mupdf/fitz/Archive.java`

MuPDF JNI 封装，用于访问归档文件（如 ZIP/TAR）。  
- `Archive`：归档类，持有 native 指针。  
- `Archive(String)`：通过路径打开归档。  
- `getFormat()`：返回归档格式。  
- `countEntries()`：条目总数。  
- `listEntry(int)`：按索引获取条目名。  
- `hasEntry(String)`：检查条目是否存在。  
- `readEntry(String)`：读取条目内容为 Buffer。

### `document/src/main/java/com/artifex/mupdf/fitz/BarcodeInfo.java`

条形码信息数据类，定义 20 种条码类型常量及内容字段。
- `BarcodeInfo`：类，持有 type 与 contents。
- `type`：条码类型整型值。
- `contents`：条码内容字符串。
- `BARCODE_*`：20 个类型常量（如 AZTEC、QRCODE 等）。
- `toString()`：native 方法返回字符串表示。

### `document/src/main/java/com/artifex/mupdf/fitz/Buffer.java`

MuPDF 原生字节缓冲区封装，支持读写、切片、保存及流操作。
- `Buffer`：类
- `getLength`/`readByte`/`readBytes`：读
- `writeByte`/`writeBytes`：写
- `slice`：切片
- `save`：保存
- `asString`：转字符串
- `readIntoStream`/`writeFromStream`：流操作

### `document/src/main/java/com/artifex/mupdf/fitz/BufferInputStream.java`

MuPDF `Buffer` 的 `InputStream` 适配器，支持标记与重置。
- `BufferInputStream`：封装 Buffer 的字节流读取
- `BufferInputStream(Buffer)`：构造
- `available()`：返回剩余字节数
- `mark(int)`、`markSupported()`、`reset()`：支持标记/重置
- `read()` 及重载：顺序读取字节

### `document/src/main/java/com/artifex/mupdf/fitz/BufferOutputStream.java`

将 MuPDF 的 `Buffer` 包装为 Java `OutputStream`。
- `BufferOutputStream`：适配器，使 `Buffer` 支持标准输出流。
- `write(byte[])` / `write(byte[], int, int)` / `write(int)`：委托给内部 `Buffer` 写入。

### `document/src/main/java/com/artifex/mupdf/fitz/ColorParams.java`

颜色参数打包与解包工具类，管理渲染意图及黑点/叠印/叠印模式标志。
- `ColorParams`：颜色参数类
- `RenderingIntent`：渲染意图枚举（4种）
- `BP`, `OP`, `OPM`：标志位常量
- `RI(int)`：从标志提取渲染意图
- `BP(int)`, `OP(int)`, `OPM(int)`：提取标志布尔值
- `pack(RenderingIntent, boolean, boolean, boolean)`：打包为标志整数

### `document/src/main/java/com/artifex/mupdf/fitz/ColorSpace.java`

穆PDF颜色空间Java绑定类，封装原生颜色空间对象。
- `ColorSpace`：颜色空间类，持有原生指针。
- `DeviceGray/RGB/BGR/CMYK`：预定义设备颜色空间静态实例。
- `fromPointer`：通过指针返回对应实例。
- `getNumberOfComponents`：获取分量数。
- `isGray/isRGB/isCMYK/isIndexed/isLab/isDeviceN/isSubtractive`：类型判断。
- `getType`：返回类型常量。
- 常量：`NONE,GRAY,RGB,BGR,CMYK,LAB,INDEXED,SEPARATION`。

### `document/src/main/java/com/artifex/mupdf/fitz/Context.java`

管理MuPDF库加载、线程上下文及全局设置。
- `Context`：初始化库与上下文
- `init()`：加载库
- `emptyStore()`/`shrinkStore(int)`：存储管理
- `enableICC()`/`disableICC()`：ICC
- `setAntiAliasLevel(int)`：抗锯齿
- `setUserCSS(String)`/`useDocumentCSS(boolean)`：CSS控制
- `getVersion()`：版本
- `setLog(Log)`：日志
- `Version`：版本信息
- `Log`：日志接口

### `document/src/main/java/com/artifex/mupdf/fitz/Cookie.java`

MuPDF 进度/错误跟踪的 Java 包装类。  
- `Cookie`：原生指针封装，提供进度/错误查询  
- `destroy()`：释放原生资源  
- `abort()`：中止操作  
- `getProgress()`：当前进度  
- `getProgressMax()`：最大进度  
- `getErrors()`：错误计数  
- `getIncomplete()`：是否未完成

### `document/src/main/java/com/artifex/mupdf/fitz/DOM.java`

MuPDF 的文档对象模型操作类，支持节点增删改查与属性管理。
- `DOM`：节点类
- `DOMAttribute`：属性键值对
- `createElement()/createTextNode()`：创建节点
- `insertBefore()/appendChild()`：插入节点
- `parent()/firstChild()/next()`：遍历节点
- `addAttribute()/attribute()`：属性操作
- `find()`：查找节点
- `getText()`：获取文本内容

### `document/src/main/java/com/artifex/mupdf/fitz/DefaultAppearance.java`

MuPDF 默认外观数据类，存储字体、大小和颜色。
- `DefaultAppearance`：数据类
- `font`：字体名称
- `size`：字号
- `color`：颜色数组

### `document/src/main/java/com/artifex/mupdf/fitz/DefaultColorSpaces.java`

管理 MuPDF 默认颜色空间（Gray/RGB/CMYK）及输出意图的 Native 封装类。
- `DefaultColorSpaces`：封装 native 指针，提供设置/获取默认颜色空间的接口。
- `setDefaultGray`/`setDefaultRGB`/`setDefaultCMYK`/`setOutputIntent`：设置对应颜色空间。
- `getDefaultGray`/`getDefaultRGB`/`getDefaultCMYK`/`getOutputIntent`：获取对应颜色空间。

### `document/src/main/java/com/artifex/mupdf/fitz/Device.java`

MuPDF设备抽象类，定义渲染回调接口。  
- `Device`：抽象基类，提供绘制方法  
- `fillPath`、`strokePath`等：绘制回调  
- `BLEND_*`：混合模式常量  
- `DEVICE_FLAG_*`：设备标志常量  
- `STRUCTURE_*`：结构元素类型常量  
- `METATEXT_*`：元文本类型常量

### `document/src/main/java/com/artifex/mupdf/fitz/DisplayList.java`

MuPDF 显示列表的 JNI 封装，支持渲染、文本提取、搜索和条形码解码。
- `DisplayList`：封装原生显示列表
- `toPixmap`：渲染为像素图
- `toStructuredText`：提取结构化文本
- `search`：搜索文本返回位置
- `run`：播放显示列表到设备
- `decodeBarcode`：解码条形码
- `destroy`：释放原生资源

### `document/src/main/java/com/artifex/mupdf/fitz/DisplayListDevice.java`

将绘制命令记录到 DisplayList 的 MuPDF 设备封装。
- `DisplayListDevice`：继承 NativeDevice，接收 DisplayList 参数
- `newNative(DisplayList)`：创建原生设备实例

### `document/src/main/java/com/artifex/mupdf/fitz/Document.java`

Document 是 MuPDF 文档的 Java 封装，提供打开、页面、元数据、权限等操作。
- `openDocument`：打开文档（多种重载）
- `countPages`/`loadPage`：页面计数与加载
- `META_*`：元数据键常量
- `PERMISSION_*`：权限常量
- `resolveLink`：解析链接至位置
- `getMetaData`/`setMetaData`：元数据读写
- `makeBookmark`/`findBookmark`：书签
- `layout`：重排文档
- `outlineIterator`：大纲迭代器
- `hasPermission`：权限检查

### `document/src/main/java/com/artifex/mupdf/fitz/DocumentWriter.java`

用于创建文档输出写入器，支持文件、流或缓冲区。
- `DocumentWriter`：构造函数，接受文件名/SeekableOutputStream/Buffer及格式选项。
- `beginPage`：开始新页，传入裁切框。
- `endPage`：结束当前页。
- `close`：关闭输出。
- `OCRListener`：OCR进度回调接口。
- `addOCRListener`：注册OCR监听器。

### `document/src/main/java/com/artifex/mupdf/fitz/DrawDevice.java`

将 MuPDF 内容渲染到 Pixmap 的绘制设备。
- `DrawDevice`：继承 NativeDevice，接收 Matrix 与 Pixmap 进行绘制。

### `document/src/main/java/com/artifex/mupdf/fitz/FileStream.java`

文件流，支持随机读写与定位，封装 RandomAccessFile。
- `FileStream`：实现 SeekableInputStream/OutputStream 接口
- `read`：读取字节
- `write`：写入字节
- `seek`：移动文件指针
- `position`：获取当前位置
- `close`：关闭文件
- `truncate`：截断当前位置以后的内容

### `document/src/main/java/com/artifex/mupdf/fitz/FitzInputStream.java`

MuPDF 原生流的 Java InputStream 适配器。
- `FitzInputStream`：封装原生流指针，提供标准读取
- `read()`/`read(byte[])`：读取字节
- `markSupported()`/`mark(int)`/`reset()`：支持标记与重置
- `available()`：可读字节数
- `close()`：关闭流并释放资源

### `document/src/main/java/com/artifex/mupdf/fitz/Font.java`

MuPDF Java 字体封装类，提供创建、查询和字形度量功能。
- `Font`：字体对象，包含名称、样式、编码与度量方法。
- `SIMPLE_ENCODING_*`：简单编码常量（拉丁、希腊、西里尔）。
- `ADOBE_*`：Adobe CJK 字体集合常量。
- `Font(String name, int index)`：按名称和索引构造字体。
- `getName()`：获取字体名称。
- `isMono/isSerif/isBold/isItalic`：样式查询。
- `encodeCharacter(int unicode)`：Unicode 到字形编码转换。
- `advanceGlyph(int glyph, boolean wmode)`：获取字形步进宽度。

### `document/src/main/java/com/artifex/mupdf/fitz/Image.java`

MuPDF 的 Java 图像封装类，管理原生图像指针。
- `Image`：图像类，从 Pixmap/文件/字节/缓冲区创建。
- `getWidth`/`getHeight`：尺寸。
- `getColorSpace`：色彩空间。
- `getNumberOfComponents`：颜色分量数。
- `getBitsPerComponent`：位深。
- `getImageMask`/`getInterpolate`：图像属性。
- `toPixmap`：转为 Pixmap。

### `document/src/main/java/com/artifex/mupdf/fitz/Link.java`

表示PDF链接，含URI与边界框。
- `Link`：链接类，封装指针与URI/矩形边界。
- `getBounds`/`setBounds`：获取/设置链接区域。
- `getURI`/`setURI`：获取/设置链接URI。
- `isExternal`：判断URI是否外部链接。
- `destroy`：释放原生资源。

### `document/src/main/java/com/artifex/mupdf/fitz/LinkDestination.java`

PDF链接目标模型，定义目标类型与坐标参数。
- `LinkDestination`：继承Location，表示链接目标。
- 常量 `LINK_DEST_FIT` 等：8种目标适配类型。
- 工厂方法：`Fit`、`FitB`、`XYZ`、`FitH`、`FitBH`、`FitV`、`FitBV`、`FitR`，构造对应目标。
- 属性：`type`、`x`、`y`、`width`、`height`、`zoom`。
- 辅助方法：`hasX`、`hasY`、`hasZoom`、`hasWidth`、`hasHeight`。

### `document/src/main/java/com/artifex/mupdf/fitz/Location.java`

表示文档中章节与页码的位置。
- `Location`：包含章节和页码的不可变位置类
- `chapter`：章节编号
- `page`：页码

### `document/src/main/java/com/artifex/mupdf/fitz/Matrix.java`

## 2D仿射变换矩阵类，支持平移、缩放、旋转、求逆

- `Matrix`：6参数仿射矩阵 `[a b c d e f]`
- `a, b, c, d, e, f`：公开字段，表示变换参数
- `concat(Matrix)`：矩阵相乘
- `scale/translate/rotate`：原地缩放/平移/旋转
- `invert()`：原地求逆
- `static Identity/Scale/Translate/Rotate/Inverted`：工厂方法

### `document/src/main/java/com/artifex/mupdf/fitz/MultiArchive.java`

文件职责：MuPDF 复合归档类，可挂载多个子归档。

- `MultiArchive`：继承 `Archive`，支持挂载子归档。
- `mountArchive(Archive sub, String path)`：挂载子归档到指定路径。

### `document/src/main/java/com/artifex/mupdf/fitz/NativeDevice.java`

MuPDF 渲染设备本地接口，声明 native 绘制与状态方法。
- `NativeDevice`：本地设备，含绘制方法（fillPath, strokePath, fillText, strokeText, fillImage, fillShade）和状态方法（beginGroup, endGroup, beginMask, endMask, close）

### `document/src/main/java/com/artifex/mupdf/fitz/Outline.java`

表示PDF文档大纲条目的数据类，含标题、URI和子节点。
- `Outline`：大纲节点类  
- `title`：节点标题  
- `uri`：目标链接  
- `down`：子节点数组  
- `r, g, b, flags`：颜色与标志位  
- 构造函数：支持无/有颜色标志的初始化

### `document/src/main/java/com/artifex/mupdf/fitz/OutlineIterator.java`

提供PDF大纲（目录）导航与编辑的迭代器。
- `OutlineIterator`：迭代器，含`next`/`prev`/`up`/`down`移动方法
- `insert`：插入大纲项，支持重载
- `update`：更新当前项
- `item`：获取当前项
- `delete`：删除当前项
- `ITERATOR_DID_NOT_MOVE`等常量：遍历状态
- `FLAG_BOLD`/`FLAG_ITALIC`：格式标志
- `OutlineItem`：内部类，描述标题、uri、展开状态、颜色及标志

### `document/src/main/java/com/artifex/mupdf/fitz/PDFAnnotation.java`

PDF 注释类，封装属性、渲染、编辑与事件处理。
- `PDFAnnotation`：PDF 注释核心类
- `TYPE_*`：注释类型常量（文本、链接等）
- `getType`/`setContents`：读写常规属性
- `toPixmap`/`toDisplayList`：渲染注释
- `applyRedaction`：应用编辑操作
- `setAppearance`：设置外观流

### `document/src/main/java/com/artifex/mupdf/fitz/PDFDocument.java`

PDF文档创建、编辑、保存及表单操作类。
- `PDFDocument`：核心类
- 常量：`LANGUAGE_*`、`PAGE_LABEL_*`、`LAYER_UI_*`、`ZUGFERD_*`
- 方法：`findPage`、`addPage`、`save`、`enableJs`、`undo`/`redo`、`addEmbeddedFile`、`subsetFonts`、`bake`
- 内部接口：`JsEventListener`、`PDFFilespecParams`、`LayerConfigUIInfo`

### `document/src/main/java/com/artifex/mupdf/fitz/PDFGraftMap.java`

封装PDF文档间对象/页面嫁接的映射句柄。
- `PDFGraftMap`：嫁接映射句柄类
- `destroy()`：释放原生资源
- `graftObject`：嫁接单个对象
- `graftPage`：嫁接页面到目标文档

### `document/src/main/java/com/artifex/mupdf/fitz/PDFObject.java`

PDF 对象的 Java 封装，提供类型检测、取值、字典/数组访问及流读写。
- `PDFObject`：MuPDF PDF 对象封装类
- `Null`：null 对象单例
- `isBoolean/isInteger/...`：类型检测
- `asBoolean/asInteger/...`：值提取
- `get(String)/get(int)`：字典/数组访问
- `put(String/index, ...)`：字典/数组写入
- `readStream/readRawStream`：读取流内容
- `push`：数组追加
- `iterator`：返回迭代器
- `PDFObjectIterator`：内部迭代器

### `document/src/main/java/com/artifex/mupdf/fitz/PDFPage.java`

PDF 页面类，封装注释、编辑、链接、表单及渲染操作。
- `PDFPage`：PDF 页面 API
- `getAnnotations()`：获取注释
- `createAnnotation()`：创建注释
- `deleteAnnotation()`：删除注释
- `applyRedactions()`：应用编辑
- `getWidgets()`：获取表单控件
- `activateWidgetAt()`：激活控件
- `createSignature()`：创建签名域
- `getTransform()`：获取变换矩阵
- `setPageBox()`：设置页面框
- `toPixmap()`：渲染为位图
- `process()`：处理页面
- `createLink*`：创建链接
- `REDACT_*` 常量：编辑模式

### `document/src/main/java/com/artifex/mupdf/fitz/PDFProcessor.java`

定义处理PDF内容流操作符的抽象接口。
- `PDFProcessor`：抽象类，声明所有PDF操作符处理方法。
- `pushResources`/`popResources`：资源栈管理。
- `op_q`/`op_Q`：图形状态栈。
- `op_cm`：设置变换矩阵。
- `op_Tf`/`op_Tj`：字体与文本绘制。
- `op_Do_image`/`op_Do_form`：绘制图像/表单。

### `document/src/main/java/com/artifex/mupdf/fitz/PDFWidget.java`

PDF表单控件类，封装字段类型、标志、值操作与签名验证。
- `PDFWidget`：表单控件类
- `TYPE_TEXT`等：字段类型常量
- `FIELD_IS_READ_ONLY`等：标志常量
- `getValue`：获取字段值
- `setValue`：设置字段值
- `toggle`：按钮切换
- `sign`：签名
- `verify`：验证签名
- `TextWidgetLayout`：文本布局结构

### `document/src/main/java/com/artifex/mupdf/fitz/PKCS7DistinguishedName.java`

表示PKCS7证书签名者的可分辨名称。
- `PKCS7DistinguishedName`：类，含证书主体字段
- `cn`：通用名称
- `o`：组织
- `ou`：组织单元
- `email`：签名者邮箱
- `c`：国家

### `document/src/main/java/com/artifex/mupdf/fitz/PKCS7Signer.java`

定义 MuPDF 数字签名抽象类，提供签名、名称和摘要大小接口。
- `PKCS7Signer`：抽象基类，封装本地签名器指针。
- `name()`：返回签名者专有名称。
- `sign(FitzInputStream)`：对输入流执行签名，返回字节数组。
- `maxDigest()`：返回签名摘要所需最大字节数。
- `destroy()`：释放本地资源。

### `document/src/main/java/com/artifex/mupdf/fitz/PKCS7Verifier.java`

定义 PKCS7 签名验证抽象类及结果码常量。
- `PKCS7Verifier`：抽象类，封装签名验证逻辑。
- `PKCS7VerifierOK`等常量：验证结果码（0~6, -1）。
- `checkDigest`：抽象方法，校验摘要。
- `checkCertificate`：抽象方法，检查证书。
- `newNative`：本地方法，创建原生实例。
- `finalize`：本地方法，释放原生资源。

### `document/src/main/java/com/artifex/mupdf/fitz/Page.java`

代表一个MuPDF文档页面，支持渲染、文本提取与链接操作。
- `Page`：页面类
- `MEDIA_BOX`等常量：边界框类型
- `getBounds()`：获取边界框
- `run(Device,Matrix)`：绘制页面
- `toPixmap()`：渲染为位图
- `toDisplayList()`：生成显示列表
- `toStructuredText()`：提取结构化文本
- `search()`：搜索文本
- `getLinks()`：获取链接
- `createLink()`/`deleteLink()`：创建/删除链接
- `getLabel()`：获取标签
- `decodeBarcode()`：解码条形码``

### `document/src/main/java/com/artifex/mupdf/fitz/Path.java`

MuPDF 矢量路径封装，提供构建、变换、遍历及边界查询。
- `Path`：实现 `PathWalker` 的矢量路径类
- `moveTo`/`lineTo`/`curveTo`/`rect`/`closePath`：添加路径段
- `walk`：遍历路径段，回调 `PathWalker`
- `getBounds`：获取路径边界框
- `transform`：对路径应用矩阵变换

### `document/src/main/java/com/artifex/mupdf/fitz/PathWalker.java`

定义路径漫步回调接口，用于遍历路径元素。
- `PathWalker`：路径漫步接口
- `moveTo`：移动到点
- `lineTo`：直线到点
- `curveTo`：三次贝塞尔曲线
- `closePath`：闭合路径

### `document/src/main/java/com/artifex/mupdf/fitz/Pixmap.java`

封装 MuPDF 原生像素图，提供创建、导出、编辑与条码识别功能。
- `Pixmap`：多构造函数，支持颜色空间/尺寸/透明度
- `clear`/`invert`/`gamma`/`tint`：图像处理
- `asPNG`/`asJPEG`/`saveAsPNG`等：格式导出与保存
- `getWidth`/`getSamples`/`getPixels`：属性访问
- `deskew`/`warp`/`autowarp`：几何校正
- `detectSkew`：倾斜检测
- `decodeBarcode`/`encodeBarcode`：条码编解码
- `DESKEW_BORDER_INCREASE`等常量：倾斜边框策略

### `document/src/main/java/com/artifex/mupdf/fitz/Point.java`

表示二维坐标点，支持矩阵变换。  
- `Point`：坐标类，含公开x/y浮点字段。  
- `transform`：用矩阵乘法变换自身坐标。  
- `equals`/`hashCode`：基于x,y判等。

### `document/src/main/java/com/artifex/mupdf/fitz/Quad.java`

表示PDF中的四边形，提供坐标存储、变换及包含判断。
- `Quad`：四边形类，含四个角点坐标
- `Quad(Rect)`：从矩形构造四边形
- `toRect()`：转为包围矩形
- `transformed(Matrix)`：返回变换后新四边形
- `transform(Matrix)`：原地矩阵变换
- `contains(float, float)`：点是否在四边形内
- `isValid()/isInfinite()/Infinite()/Invalid()`：状态与特殊值工厂

### `document/src/main/java/com/artifex/mupdf/fitz/Rect.java`

表示MuPDF的浮点矩形，提供变换、包含、合并等几何运算。
- `Rect`：浮点矩形类，字段`x0,y0,x1,y1`
- `MIN_INF_RECT`/`MAX_INF_RECT`：极值常量
- `transform`：矩阵变换
- `union`：合并矩形
- `contains`：点/矩形包含判断
- `inset`/`offset`：缩放/平移
- `Infinite`/`Empty`/`Invalid`：静态工厂方法

### `document/src/main/java/com/artifex/mupdf/fitz/RectI.java`

mudf 整数矩形，支持变换、包含、合并等操作。
- `RectI`：整数矩形类
- `x0,y0,x1,y1`：坐标
- `transform(Matrix)`：矩阵变换
- `contains()`：点/矩形包含测试
- `union(RectI)`：合并矩形
- `inset/offset/offsetTo`：内缩/偏移
- `isEmpty/isValid/isInfinite`：状态判断
- `Infinite/Empty/Invalid`：静态工厂

### `document/src/main/java/com/artifex/mupdf/fitz/SeekableInputOutputStream.java`

合并可随机读写的流接口，继承 SeekableOutputStream 和 SeekableInputStream。  
- `SeekableInputOutputStream`：统一随机读写流接口。

### `document/src/main/java/com/artifex/mupdf/fitz/SeekableInputStream.java`

定义可定位输入流接口，扩展 SeekableStream 增加 read 功能。
- `SeekableInputStream`：接口，继承 SeekableStream，添加 `read(byte[] b)` 方法。

### `document/src/main/java/com/artifex/mupdf/fitz/SeekableOutputStream.java`

可定位输出流接口，继承SeekableStream，提供写入字节和截断。
- `SeekableOutputStream`：可寻址输出流接口
- `write(byte[] b, int off, int len)`：写入字节数组指定部分
- `truncate()`：在当前位置截断流

### `document/src/main/java/com/artifex/mupdf/fitz/SeekableStream.java`

可寻址输入流接口，定义随机访问与位置查询。
- `SeekableStream`：接口
- `SEEK_SET`：基于文件头偏移
- `SEEK_CUR`：基于当前位置偏移
- `SEEK_END`：基于文件尾偏移
- `seek`：移动流位置
- `position`：获取当前流位置

### `document/src/main/java/com/artifex/mupdf/fitz/Shade.java`

MuPDF 着色器类的 JNI 包装，管理原生指针并提供销毁/边界查询。

- `Shade`：封装原生 fz_shade 指针
- `destroy()`：释放原生资源
- `getBounds(Matrix)`：返回着色器在指定变换下的边界矩形

### `document/src/main/java/com/artifex/mupdf/fitz/Story.java`

HTML内容布局器，提供放置、绘制与DOM访问。
- `Story`：构造函数，接受内容/CSS/em/归档
- `place()`：内容放置，返回状态码
- `draw()`：绘制到设备
- `document()`：返回DOM
- `FLAGS_NO_OVERFLOW`：无溢出标志
- `ALL_FITTED`：放置成功返回码
- `OVERFLOW_WIDTH`：宽度溢出返回码

### `document/src/main/java/com/artifex/mupdf/fitz/StrokeState.java`

描边状态类，封装线帽、线连接、线宽等参数并提供原生交互。
- `StrokeState`：描边状态类，含构造、销毁及getter
- `LINE_CAP_BUTT/ROUND/SQUARE/TRIANGLE`：线帽风格常量
- `LINE_JOIN_MITER/ROUND/BEVEL/MITER_XPS`：线连接风格常量
- `getLineCap()`等：获取描边参数

### `document/src/main/java/com/artifex/mupdf/fitz/StructuredText.java`

封装 MuPDF 结构化文本，提供搜索、选择、复制与遍历功能。
- `StructuredText`：结构化文本数据类
- `search`：文本搜索，返回 Quad 数组
- `highlight`：区域高亮
- `snapSelection`：选择对齐
- `copy`：复制文本
- `walk`：遍历结构
- `asJSON`/`asHTML`/`asText`：导出格式
- `getBlocks`：获取文本块
- `TextBlock`/`TextLine`/`TextChar`：文本块/行/字符数据类
- `SELECT_*`/`SEARCH_*`/`VECTOR_*`：选择模式与搜索标志常量

### `document/src/main/java/com/artifex/mupdf/fitz/StructuredTextWalker.java`

StructuredText 遍历回调接口。  
- `StructuredTextWalker`：结构化文本遍历回调接口  
- `onImageBlock`：处理图像块  
- `beginTextBlock`/`endTextBlock`：文本块开始/结束  
- `beginLine`/`endLine`：行开始/结束  
- `onChar`：处理字符  
- `beginStruct`/`endStruct`：结构开始/结束  
- `onVector`：处理矢量图形  
- `VectorInfo`：矢量信息（是否描边、是否矩形）

### `document/src/main/java/com/artifex/mupdf/fitz/Text.java`

表示 MuPDF 文本对象，支持字形/字符串添加、边界计算与遍历。
- `Text`：实现 TextWalker 的文本收集类
- `showGlyph`：添加单个字形
- `showString`：添加字符串
- `getBounds`：获取文本边界
- `walk`：遍历文本内容
- `destroy`：释放原生资源

### `document/src/main/java/com/artifex/mupdf/fitz/TextWalker.java`

定义文本遍历接口，处理字形显示。
- `TextWalker`：文本遍历器接口
- `showGlyph`：显示字形回调方法

### `document/src/main/java/com/artifex/mupdf/fitz/TreeArchive.java`

这是一个 MuPDF 内存树存档，可动态添加条目。  
- `TreeArchive`：基于树结构的存档类，继承 `Archive`  
- `add(String name, Buffer buf)`：向存档添加命名数据块

### `document/src/main/java/com/artifex/mupdf/fitz/TryLaterException.java`

定义稍后重试的运行时异常，无额外字段。
- `TryLaterException`：继承 RuntimeException，表示操作需稍后重试。

### `document/src/main/java/com/artifex/mupdf/fitz/android/AndroidDrawDevice.java`

Android 上渲染 MuPDF 页面到 `Bitmap` 的 NativeDevice 子类。
- `AndroidDrawDevice`：将页面渲染到安卓 Bitmap
- `drawPage`：直接渲染 Page 为 Bitmap（支持矩阵/DPI/旋转）
- `fitPage`/`fitPageWidth`：计算缩放矩阵适应宽高
- `drawPageFit`/`drawPageFitWidth`：渲染适应页面
- `invertLuminance`：JNI 反转亮度

### `document/src/main/java/com/artifex/mupdf/fitz/android/AndroidImage.java`

实现从 Android Bitmap 创建 MuPDF 图像对象的适配类。
- `AndroidImage`：继承 Image，封装 Android Bitmap 为 MuPDF 图像
- `AndroidImage(Bitmap, AndroidImage)`：构造函数，调用 native 方法初始化
- `newAndroidImageFromBitmap`：native 方法，从 Bitmap 和 mask 生成图像指针

### `document/src/main/java/me/rerere/document/DocxParser.kt`

解析 DOCX 文件并转换为 Markdown 文本。
- `DocxParser`：单例对象，提供 DOCX 解析功能
- `parse`：将 DOCX 文件转为 Markdown 字符串

### `document/src/main/java/me/rerere/document/EpubParser.kt`

解析EPUB文件提取文本并转换为Markdown格式。
- `EpubParser`：解析器单例
- `parse`：入口，返回Markdown内容
- `ManifestItem`：清单项数据类
- `findOpfPath`：查找OPF文件路径
- `parseOpf`：解析清单与书脊
- `parseXhtml`：XHTML转Markdown

### `document/src/main/java/me/rerere/document/PdfParser.kt`

PDF文本解析工具，提取所有页面纯文本内容。
- `PdfParser`：单例对象，封装PDF解析
- `parserPdf`：打开PDF文件，遍历每页，返回带页码标记的全文

### `document/src/main/java/me/rerere/document/PptxParser.kt`

解析 PPTX 文件提取文本内容，支持幻灯片、备注、列表和表格。
- `PptxParser`：PPTX 解析单例
- `parse(file)`：将 PPTX 转为 Markdown 文本

### `document/src/test/java/me/rerere/document/ExampleUnitTest.kt`

示例单元测试，验证基本断言功能。
- `ExampleUnitTest`：示例测试类
- `addition_isCorrect`：验证 2+2=4
