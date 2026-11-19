locals {
  # Repository root: one level above this Terraform configuration directory.
  repo_root = abspath("${path.module}/..")

  function_network_configured = var.function_group_id != "" && var.function_network_id != ""
  gemini_region               = var.gemini_db_region != "" ? var.gemini_db_region : var.region

  # APIG 环境：共享独享实例上同名环境只能存在一个。提供共享环境 ID 时直接
  # 复用，否则本目录自建；引用环境的资源统一使用 local.apig_env_id。
  use_shared_apig_env = var.shared_apig_env_id != ""
  apig_env_id         = local.use_shared_apig_env ? var.shared_apig_env_id : huaweicloud_apig_environment.orders[0].id

  # 注入函数的环境变量（值必须为字符串）。
  function_env = {
    TABLE_NAME        = var.orders_table
    PAGE_SIZE         = "10"
    GEMINI_ENDPOINT   = var.gemini_db_endpoint
    GEMINI_PORT       = tostring(var.gemini_db_port)
    GEMINI_PROTOCOL   = var.gemini_db_protocol
    GEMINI_REGION     = local.gemini_region
    GEMINI_ACCESS_KEY = var.gemini_access_key
    GEMINI_SECRET_KEY = var.gemini_secret_key
  }

  # 五个函数共用同一份构建产物，仅入口不同。
  functions = {
    create-order = {
      handler = "com.example.jora.handler.CreateOrderHandler.handleRequest"
    }
    get-order = {
      handler = "com.example.jora.handler.GetOrderHandler.handleRequest"
    }
    list-orders = {
      handler = "com.example.jora.handler.GetOrdersHandler.handleRequest"
    }
    update-order = {
      handler = "com.example.jora.handler.UpdateOrderHandler.handleRequest"
    }
    delete-order = {
      handler = "com.example.jora.handler.DeleteOrderHandler.handleRequest"
    }
  }

  api_routes = {
    create_order = {
      method   = "POST"
      path     = "/orders"
      function = "create-order"
    }
    get_order = {
      method   = "GET"
      path     = "/orders/{order_id}"
      function = "get-order"
    }
    list_orders = {
      method   = "GET"
      path     = "/orders"
      function = "list-orders"
    }
    update_order = {
      method   = "POST"
      path     = "/orders/{order_id}"
      function = "update-order"
    }
    delete_order = {
      method   = "DELETE"
      path     = "/orders/{order_id}"
      function = "delete-order"
    }
  }
}

# ---------------------------------------------------------------------------
# 日志：显式创建 LTS 组与每函数一个日志流，并在函数上绑定四参数，
# 否则平台不会自动建组，日志会丢失。
# ---------------------------------------------------------------------------

resource "huaweicloud_lts_group" "orders" {
  group_name  = "${var.app_name}-logs"
  ttl_in_days = 7
}

resource "huaweicloud_lts_stream" "orders" {
  for_each = local.functions

  group_id    = huaweicloud_lts_group.orders.id
  stream_name = "${var.app_name}-${each.key}"
}

# ---------------------------------------------------------------------------
# 委托：委托仅覆盖日志写入与可选的网络挂接，数据面凭据由部署参数下发。
# ---------------------------------------------------------------------------

resource "huaweicloud_identity_agency" "orders_function" {
  name                  = "${var.app_name}-func"
  description           = "Allow the order REST functions to write LTS logs"
  delegated_domain_name = "op_svc_cff"
  duration              = "FOREVER"

  project_role {
    project = var.region
    roles = concat(
      ["LTS FullAccess"],
      local.function_network_configured ? ["VPC FullAccess"] : [],
    )
  }

  lifecycle {
    ignore_changes = [delegated_service_name]
  }
}

# ---------------------------------------------------------------------------
# 函数：5 个 Java11 函数，共用同一份 ZIP（外层 ZIP 根目录仅含 function.jar）。
# 构建：python scripts/build.py
# ---------------------------------------------------------------------------

resource "huaweicloud_fgs_function" "orders" {
  for_each = local.functions

  name           = "${var.app_name}-${each.key}"
  app            = "default"
  handler        = each.value.handler
  runtime        = "Java11"
  memory_size    = 128
  timeout        = 15
  code_type      = "zip"
  func_code      = filebase64("${path.module}/../src/build/function.zip")
  user_data      = jsonencode(local.function_env)
  agency         = huaweicloud_identity_agency.orders_function.name
  enable_lts_log = true
  vpc_id         = local.function_network_configured ? var.function_group_id : null
  network_id     = local.function_network_configured ? var.function_network_id : null

  log_group_id    = huaweicloud_lts_group.orders.id
  log_group_name  = huaweicloud_lts_group.orders.group_name
  log_stream_id   = huaweicloud_lts_stream.orders[each.key].id
  log_stream_name = huaweicloud_lts_stream.orders[each.key].stream_name

  depends_on = [huaweicloud_identity_agency.orders_function]
}

# ---------------------------------------------------------------------------
# 数据表：经 GeminiDB DynamoDB 兼容接口创建订单表（apply 阶段执行）。
# destroy 阶段仅删除本项目创建的表（表名带 jora_ 前缀，见 ensure_table.py）。
# ---------------------------------------------------------------------------

resource "terraform_data" "orders_table" {
  # destroy provisioner 只能引用 self，故所需参数全部经 triggers_replace 传递。
  triggers_replace = {
    table     = var.orders_table
    endpoint  = var.gemini_db_endpoint
    port      = tostring(var.gemini_db_port)
    protocol  = var.gemini_db_protocol
    region    = local.gemini_region
    ak        = var.gemini_access_key
    sk        = var.gemini_secret_key
    proxy_url = var.gemini_proxy_url
    token     = var.gemini_proxy_token
  }

  provisioner "local-exec" {
    environment = {
      TABLE_NAME         = var.orders_table
      GEMINI_ENDPOINT    = var.gemini_db_endpoint
      GEMINI_PORT        = tostring(var.gemini_db_port)
      GEMINI_PROTOCOL    = var.gemini_db_protocol
      GEMINI_REGION      = local.gemini_region
      GEMINI_ACCESS_KEY  = var.gemini_access_key
      GEMINI_SECRET_KEY  = var.gemini_secret_key
      GEMINI_PROXY_URL   = var.gemini_proxy_url
      GEMINI_PROXY_TOKEN = var.gemini_proxy_token
    }
    command = "python ${local.repo_root}/scripts/ensure_table.py"
  }

  provisioner "local-exec" {
    when = destroy
    environment = {
      TABLE_NAME         = self.triggers_replace["table"]
      GEMINI_ENDPOINT    = self.triggers_replace["endpoint"]
      GEMINI_PORT        = self.triggers_replace["port"]
      GEMINI_PROTOCOL    = self.triggers_replace["protocol"]
      GEMINI_REGION      = self.triggers_replace["region"]
      GEMINI_ACCESS_KEY  = self.triggers_replace["ak"]
      GEMINI_SECRET_KEY  = self.triggers_replace["sk"]
      GEMINI_PROXY_URL   = self.triggers_replace["proxy_url"]
      GEMINI_PROXY_TOKEN = self.triggers_replace["token"]
    }
    command = "python ${path.module}/../src/scripts/ensure_table.py --delete"
  }
}

# ---------------------------------------------------------------------------
# APIG：分组 + 环境 + 5 个 API + 发布（实例由外部传入）。
# ---------------------------------------------------------------------------

resource "huaweicloud_apig_group" "orders" {
  instance_id           = var.apig_instance_id
  name                  = "${var.app_name}-group"
  description           = "Order REST API group"
  domain_access_enabled = true
}

resource "huaweicloud_apig_environment" "orders" {
  count = local.use_shared_apig_env ? 0 : 1

  instance_id = var.apig_instance_id
  name        = var.apig_env_name
  description = "Order REST API environment"
}

# 使用共享环境时按 ID 反查环境名（X-Stage 取值）；ID 不存在时 plan 阶段即报错
data "huaweicloud_apig_environments" "orders" {
  count       = local.use_shared_apig_env ? 1 : 0
  instance_id = var.apig_instance_id
}

locals {
  shared_apig_env_matches = [
    for env in flatten(data.huaweicloud_apig_environments.orders[*].environments) :
    env if env.id == var.shared_apig_env_id
  ]
  apig_env_name = local.use_shared_apig_env ? local.shared_apig_env_matches[0].name : huaweicloud_apig_environment.orders[0].name
}

resource "huaweicloud_apig_api" "orders" {
  for_each = local.api_routes

  instance_id             = var.apig_instance_id
  group_id                = huaweicloud_apig_group.orders.id
  type                    = "Public"
  name                    = "${var.app_name}-${each.key}"
  request_protocol        = "HTTPS"
  request_method          = each.value.method
  request_path            = each.value.path
  security_authentication = "NONE"
  matching                = "Exact"

  func_graph {
    function_urn     = trimsuffix(huaweicloud_fgs_function.orders[each.value.function].urn, ":latest")
    version          = "latest"
    network_type     = "V1"
    request_protocol = "HTTPS"
    timeout          = 15000
    invocation_type  = "sync"
  }
}

resource "huaweicloud_apig_api_publishment" "orders" {
  for_each = local.api_routes

  instance_id = var.apig_instance_id
  env_id      = local.apig_env_id
  api_id      = huaweicloud_apig_api.orders[each.key].id
  description = "Published by ${var.app_name}"
}

# 分组调试子域名：分组创建后即稳定，勿依赖发布资源。
data "huaweicloud_apig_groups" "orders" {
  instance_id = var.apig_instance_id
  group_id    = huaweicloud_apig_group.orders.id
}
