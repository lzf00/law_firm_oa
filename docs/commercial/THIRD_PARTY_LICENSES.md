# 第三方软件与许可证说明

正式制品必须同时附带 CI 生成的后端、前端和容器 SBOM，以及依赖许可证扫描结果。
本文件只说明项目直接采用的主要组件，不替代制品级清单。

| 组件 | 用途 | 许可证/核对位置 |
|---|---|---|
| Spring Boot / Spring Security | 后端框架与安全 | Apache-2.0；以 Maven SBOM 为准 |
| Flowable | 工作流 | Apache-2.0；以 Maven SBOM 为准 |
| PostgreSQL JDBC / Flyway | 数据库与迁移 | 以 Maven SBOM 为准 |
| MinIO Java SDK | 私有对象存储客户端 | Apache-2.0；以 Maven SBOM 为准 |
| Vue / Vite / Element Plus | 前端 | 以 npm SBOM 为准 |
| Nginx | 静态资源与反向代理 | BSD-2-Clause；以镜像 SBOM 为准 |
| PostgreSQL / Redis | 数据服务 | 以实际托管或镜像版本条款为准 |

发布门禁禁止未知许可证、GPL/AGPL 等未审批强互惠依赖或缺少许可证元数据的新增依赖。
发现许可证变化时由产品、技术和法律共同确认后才能发布。
