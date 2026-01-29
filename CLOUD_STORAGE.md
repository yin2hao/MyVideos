# 云存储支持更新

## 新增功能

现已支持三种云存储方式，用户可以在设置中自由选择：

### 1. WebDAV
- 支持所有标准 WebDAV 服务器
- 兼容坚果云、群晖 NAS 等
- 配置项：
  - 服务器地址
  - 用户名
  - 密码

### 2. FTP/FTPS
- 支持标准 FTP 协议
- 支持 FTPS 加密连接
- 配置项：
  - 服务器地址
  - 端口（默认 21）
  - 用户名
  - 密码
  - 是否使用 FTPS

### 3. S3 兼容存储
- 兼容 AWS S3 标准
- 支持阿里云 OSS、MinIO、腾讯云 COS 等
- 配置项：
  - S3 端点（可选，留空使用 AWS）
  - 区域
  - 存储桶名称
  - Access Key
  - Secret Key
  - Path Style 访问模式

## 架构改进

### 统一接口设计
- 创建了 `CloudStorageClient` 接口，定义了统一的云存储操作
- 所有存储实现都遵循相同的接口规范

### 工厂模式
- `CloudStorageClientFactory` 根据用户配置自动创建对应的存储客户端
- 便于后续扩展更多存储方式

### 模块化实现
- `WebDAVClientAdapter`: WebDAV 适配器
- `FTPClientImpl`: FTP/FTPS 客户端
- `S3Client`: S3 兼容存储客户端

## 使用说明

1. 打开应用设置
2. 在"云存储设置"中选择存储类型
3. 填写对应的配置信息
4. 点击"测试连接"验证配置
5. 保存设置

## 技术细节

### 依赖库
- **OkHttp**: HTTP 客户端（WebDAV、S3）
- **Apache Commons Net**: FTP 客户端
- **Kotlin Coroutines**: 异步操作

### 加密支持
所有存储方式都支持：
- AES-256-GCM 视频加密
- 分块上传
- 主密码保护

### 验证机制
每种存储方式都实现了连接测试功能，确保配置正确后才能使用。

## 后续扩展

架构设计支持轻松添加更多存储方式，只需：
1. 实现 `CloudStorageClient` 接口
2. 在 `Settings` 中添加对应配置
3. 在 `CloudStorageClientFactory` 中注册
4. 更新设置界面

可考虑添加：
- OneDrive
- Google Drive  
- Dropbox
- 百度网盘
- 自定义 HTTP API
