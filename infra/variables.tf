variable "region" {
  description = "部署区域"
  type        = string
  default     = "cn-north-4"
}

variable "app_name" {
  description = "应用前缀；所有资源名称均由该前缀派生"
  type        = string
  default     = "jora-rest"

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9-]{2,30}$", var.app_name))
    error_message = "app_name 必须以字母开头，仅含字母、数字、连字符，长度为 3~31。"
  }
}

variable "apig_instance_id" {
  description = "APIG 专享实例 ID（由外部集中创建后传入，本目录不创建实例）"
  type        = string
}

variable "apig_env_name" {
  description = "APIG 发布环境名称（仅自建环境时生效）"
  type        = string
  default     = "prod"
}

variable "shared_apig_env_id" {
  description = "已有共享 APIG 环境 ID；非空时不创建环境，直接使用该环境（独享实例上同名环境只能存在一个）"
  type        = string
  default     = ""
}

variable "orders_table" {
  description = "GeminiDB（DynamoDB 兼容接口）订单表名"
  type        = string
  default     = "jora_orders"
}

variable "gemini_db_endpoint" {
  description = "GeminiDB 数据面访问地址（主机名或含协议的 URL）"
  type        = string
}

variable "gemini_db_port" {
  description = "GeminiDB 数据面端口"
  type        = number
  default     = 8000
}

variable "gemini_db_protocol" {
  description = "GeminiDB 数据面协议（http 或 https）"
  type        = string
  default     = "http"
}

variable "gemini_db_region" {
  description = "GeminiDB 兼容接口签名所用区域；留空时使用部署区域"
  type        = string
  default     = ""
}

variable "gemini_access_key" {
  description = "GeminiDB 兼容接口访问密钥 ID"
  type        = string
  sensitive   = true
}

variable "gemini_secret_key" {
  description = "GeminiDB 兼容接口访问密钥 Secret"
  type        = string
  sensitive   = true
}

variable "gemini_proxy_url" {
  description = "共享数据面代理函数的公网 APIG 入口 URL；留空时建表脚本直连签名"
  type        = string
  default     = ""
}

variable "gemini_proxy_token" {
  description = "代理入口校验令牌（X-Proxy-Token）；与 gemini_proxy_url 同时提供"
  type        = string
  sensitive   = true
  default     = ""
}

variable "function_group_id" {
  description = "函数挂接的共享网络（虚拟私有云）ID；留空时函数不挂接网络"
  type        = string
  default     = ""
}

variable "function_network_id" {
  description = "函数挂接的共享子网 network_id；必须与 function_group_id 同时填写"
  type        = string
  default     = ""
}
