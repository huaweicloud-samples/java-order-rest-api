# 部署指南

本文给出从零到可验证部署的完整命令，每一步都附预期输出。
命令默认在本仓库根目录执行，需要进入子目录时会显式写出 `cd`。

架构与设计取舍见 [architecture.md](architecture.md)。

## 0. 约定与占位符

| 占位符 | 含义 | 取值方式 |
|-|-|-|
| `<HW_ACCESS_KEY>` | Access Key ID | 控制台「我的凭证」页面的访问密钥区域 |
| `<HW_SECRET_KEY>` | Secret Access Key | 同上；两者只填入当前 shell，不要写入代码或提交到仓库 |
| `<APIG 专享实例 ID>` | 已就绪的网关实例 | 网关控制台的实例列表 |
| `<数据面地址>` | 文档数据服务的数据面地址 | 由实例详情页获取 |

凭据在本实践中有两处用途，彼此独立：

- 部署：由执行 `terraform` 的进程读取 `HW_ACCESS_KEY`、`HW_SECRET_KEY`；
  使用临时安全令牌时另加 `HW_SECURITY_TOKEN`。
- 运行期：函数的委托按调用签发临时授权；数据面凭据由部署参数下发，函数代码不含长期密钥。

## 1. 自检本地工具

```bash
python --version
terraform version
```

预期输出（版本不低于下表要求即可）：

```text
Python 3.11.9
Terraform v1.9.8
```

要求：Terraform CLI `>= 1.3`，huaweicloud Provider `>= 1.64`，Python 3.8 及以上，
JDK 11 及以上（用于编译函数）。

## 2. 构建函数部署包

```bash
python src/scripts/build.py
```

预期输出：

```text
[PASS] Java 11 编译完成，编译期 Context 桩未入包
[PASS] function.zip 根目录仅包含 function.jar
[PASS] function.zip sha256=82b724de4feb…
```

构建说明：

- 使用 `javac --release 11` 编译，JSON 库按类并入 `function.jar`。
- 打包使用固定的条目时间戳与权限，因此同一份源码两次构建的摘要一致。
- 编译期的运行时上下文桩只参与编译，不进入部署包。
- 部署包外层 ZIP 的根目录只有 `function.jar`，这是 Java 运行时的入口约定。

## 3. 本地单元测试

```bash
python src/scripts/run_tests.py
```

预期输出结尾：

```text
passed=22 failed=0
[PASS] 单元测试全部通过
```

测试覆盖五个处理程序的成功与失败分支（400 / 404 / 409）、模型序列化、
事件解析（含 base64 请求体）以及数据层的全流程与分页。测试不依赖任何云端资源。

## 4. 静态校验

```bash
cd infra
terraform init
terraform validate
```

预期输出：

```text
Success! The configuration is valid, but there were some
validation warnings as shown above.
```

未使用官方插件镜像时，警告内容是本地开发覆盖的提示，属预期输出。

## 5. 配置访问凭据

在控制台「我的凭证」页面获取 Access Key ID 与 Secret Access Key，然后填入当前 shell：

```bash
export HW_ACCESS_KEY=<HW_ACCESS_KEY>
export HW_SECRET_KEY=<HW_SECRET_KEY>
```

使用临时安全令牌时另加：

```bash
export HW_SECURITY_TOKEN=<HW_SECURITY_TOKEN>
```

`infra/versions.tf` 不写入任何密钥内容，凭据由进程环境读取。

## 6. 提供部署参数

必填项只有四个：`apig_instance_id`、`gemini_db_endpoint`、`gemini_access_key`、`gemini_secret_key`。
其余变量按默认值即可。

```bash
export TF_VAR_apig_instance_id="<APIG 专享实例 ID>"
export TF_VAR_gemini_db_endpoint="<数据面地址>"
export TF_VAR_gemini_access_key="<数据面访问密钥 ID>"
export TF_VAR_gemini_secret_key="<数据面访问密钥 Secret>"
```

### 6.1 主要参数

| 变量 | 类型 | 默认值 | 必填 | 说明 |
|-|-|-|-|-|
| `region` | string | `cn-north-4` | 否 | 部署区域 |
| `app_name` | string | `jora-rest` | 否 | 所有资源名称前缀；字母开头，可含数字与连字符，长度 3~31 |
| `apig_instance_id` | string | 无 | 是 | 网关专享实例 ID；本目录只引用不创建 |
| `apig_env_name` | string | `prod` | 否 | 发布环境名（仅自建环境时生效） |
| `shared_apig_env_id` | string | 空 | 否 | 已有共享环境 ID；非空时不创建环境 |
| `orders_table` | string | `jora_orders` | 否 | 订单表名 |
| `gemini_db_endpoint` | string | 无 | 是 | 数据面地址 |
| `gemini_db_port` | number | `8000` | 否 | 数据面端口 |
| `gemini_db_protocol` | string | `http` | 否 | `http` 或 `https` |
| `gemini_db_region` | string | 空 | 否 | 签名区域；留空回落到 `region` |
| `gemini_access_key` | string | 无 | 是 | 数据面访问密钥 ID（敏感） |
| `gemini_secret_key` | string | 无 | 是 | 数据面访问密钥 Secret（敏感） |
| `function_group_id` | string | 空 | 否 | 函数挂接的网络 ID；与 `function_network_id` 成对 |
| `function_network_id` | string | 空 | 否 | 函数挂接的子网 network_id；与 `function_group_id` 成对 |

### 6.2 几处容易填错的位置

- `app_name` 决定全部资源名，改它等于整套重部署；长度上限 31，含连字符。
- `shared_apig_env_id` 与 `apig_env_name` 只生效一个：给出共享环境 ID 时不再创建环境。
- 函数挂接网络的两个变量必须成对，只填一个会导致函数挂接失败。
- 数据面凭据随部署包进入函数运行期配置，状态文件需按密文管理。

## 7. 创建资源

```bash
cd infra
terraform plan -out=jora.tfplan
terraform apply jora.tfplan
```

`terraform plan` 预期会创建以下资源：

```text
Plan: 25 to add, 0 to change, 0 to destroy.
```

构成：1 个日志组与 5 条日志流、1 个委托、5 个函数、1 个建表动作、
1 个分组、1 个环境（使用共享环境时为 0）、5 条 API、5 条发布记录。

`terraform apply` 完成后预期输出：

```text
Apply complete! Resources: 25 added, 0 changed, 0 destroyed.
```

## 8. 读取部署输出

```bash
terraform output
```

预期列出以下输出名（与 `infra/outputs.tf` 一致）：

```text
api_base_url
api_paths
apig_env_id
apig_env_name
apig_instance_id
data_plane_status
function_urns
log_group_id
log_stream_ids
orders_table
region
```

常用取值：

```bash
terraform output api_base_url        # 订单 API 基地址
terraform output apig_env_name       # 调用非 RELEASE 环境时的 X-Stage 取值
terraform output orders_table        # 订单表名
terraform output function_urns       # 五个函数的 URN
terraform output data_plane_status   # 数据面配置提示（不回显凭据）
```

## 9. 验证部署

```bash
python src/scripts/verify_e2e.py
```

脚本读取部署输出，对真实网关地址跑七步校验，并把结果写入 `verify-result.json`。
预期输出结尾：

```text
[PASS] 7/7 checks passed
```

七步覆盖：创建（201 + 订单标识 + 版本 1）、查询、列表包含新订单、
旧版本更新被拒（409）、更新（版本 2）、删除（返回被删订单）、删除后再查（404）。
Java 冷启动可能让首次调用超时，脚本内置重试退避，因此首次运行耗时较长属正常。

## 10. 手工验证示例

非 RELEASE 环境的调用必须携带 `X-Stage` 请求头。先取基地址：

```bash
export BASE="$(terraform output -raw api_base_url)"
```

创建订单：

```bash
curl -X POST "$BASE/orders" -H "X-Stage: prod" -H "Content-Type: application/json" \
  -d '{"customerId":"c-1","preTaxAmount":100,"postTaxAmount":120}'
```

预期返回 201 与订单体，其中 `version` 为 1：

```json
{"orderId":"<订单标识>","customerId":"c-1","preTaxAmount":100,"postTaxAmount":120,"version":1}
```

查询、列表与更新：

```bash
curl "$BASE/orders/<订单标识>" -H "X-Stage: prod"
curl "$BASE/orders" -H "X-Stage: prod"
curl -X POST "$BASE/orders/<订单标识>" -H "X-Stage: prod" -H "Content-Type: application/json" \
  -d '{"customerId":"c-1","preTaxAmount":200,"postTaxAmount":240,"version":1}'
```

更新成功后 `version` 变为 2；用旧版本号再次更新将返回 409。

删除并确认：

```bash
curl -X DELETE "$BASE/orders/<订单标识>" -H "X-Stage: prod"
curl "$BASE/orders/<订单标识>" -H "X-Stage: prod"     # 预期 404
```

**部署完成后请按本节步骤在真实环境自行验证，确认功能可用后方可视为交付完成。**

## 11. 更新函数代码

```bash
python src/scripts/build.py
cd infra
terraform apply
```

部署包内容变化会触发五个函数更新（函数以 `filebase64` 读取部署包）。
预期输出包含五个函数的就地更新。

## 12. 释放资源

```bash
cd infra
terraform destroy
```

预期输出：

```text
Destroy complete! Resources: 25 destroyed.
```

销毁会删除函数、分组/环境/API/发布、日志组与日志流、委托，并调用建表脚本的删除分支。
删除只作用于带本项目前缀的表，共享实例上其他项目的表不受影响。
网关专享实例与文档数据实例属外部资源，不在本目录的清理范围，需按各自流程处理。

## 13. 故障排查

| 现象 | 定位方向 |
|-|-|
| 函数报「找不到入口」 | 部署包外层 ZIP 根目录必须只有 `function.jar`；入口写全限定类名加点号与 `handleRequest` |
| 调用返回 404 且路径正确 | 是否遗漏 `X-Stage` 请求头；非 RELEASE 环境必须指定 |
| 调用返回 500 且日志报签名错误 | 核对 `gemini_db_region` 与数据面所在区域是否一致 |
| 建表失败 | 核对数据面地址与端口可达性；私网地址需要函数挂接同一网络 |
| 首次调用超时 | Java 冷启动所致，重试即可；持续超时检查函数超时配置与数据面连通性 |
| 更新返回 409 | 版本号已过期，先查询取当前版本再更新 |
| 日志为空 | 五个函数必须同时绑定日志组 ID/名称与日志流 ID/名称四个参数 |
