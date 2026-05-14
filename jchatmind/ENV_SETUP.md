# 环境变量配置说明

## 概述

为了保护敏感的 API 密钥和数据库密码，本项目使用 `.env` 文件来管理环境变量。

## 配置步骤

### 1. 复制示例文件

```bash
cp .env.example .env
```

### 2. 编辑 .env 文件

打开 `.env` 文件，填入你的实际配置信息：

```env
# Database Configuration
DB_PASSWORD=your_database_password

# Email Configuration
EMAIL_PASSWORD=your_email_authorization_code

# AI API Keys
DEEPSEEK_API_KEY=your_deepseek_api_key
ZHIPUAI_API_KEY=your_zhipuai_api_key
OPENAI_API_KEY=your_openai_api_key

# Map API Key (if needed)
AMAP_API_KEY=your_amap_api_key
```

### 3. 重启应用

修改 `.env` 文件后，需要重启 Spring Boot 应用才能使配置生效。

## 安全注意事项

- **永远不要**将 `.env` 文件提交到版本控制系统
- `.env` 文件已添加到 `.gitignore` 中
- 每个开发者应该有自己的 `.env` 文件
- 在生产环境中，建议使用更安全的环境变量管理方式

## 配置文件说明

- `.env` - 本地环境变量配置文件（不提交到 Git）
- `.env.example` - 环境变量配置模板（提交到 Git）
- `application.yaml` - Spring Boot 配置文件，使用 `${VAR_NAME:default_value}` 语法引用环境变量

## 故障排除

如果应用无法启动，请检查：
1. `.env` 文件是否存在于项目根目录
2. 所有必需的环境变量是否已正确设置
3. 环境变量名称是否与 `application.yaml` 中的引用一致
