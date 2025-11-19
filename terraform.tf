variable "cloud_id" {
  description = "Yandex Cloud ID"
  type        = string
}

variable "folder_id" {
  description = "Yandex Cloud folder ID"
  type        = string
}

variable "tg_bot_key" {
  description = "Telegram Bot API token"
  type        = string
  sensitive   = true
}

variable "instruction_file_path" {
  description = "Local path to instruction file for YandexGPT"
  type        = string
}

variable "service_account_id" {
  description = "Service account id"
  type        = string
}

terraform {
  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = ">= 0.95.0"
    }
    archive = {
      source = "hashicorp/archive"
    }
    null = {
      source = "hashicorp/null"
    }
  }
}

provider "yandex" {
  cloud_id                  = var.cloud_id
  folder_id                 = var.folder_id
  service_account_key_file  = pathexpand("~/.yc-keys/key.json")
}

resource "yandex_storage_bucket" "bot_gpt_instructions" {
  bucket = "${var.folder_id}-gpt-instructions"
  folder_id = var.folder_id
  anonymous_access_flags {
    read        = true
  }
}

resource "yandex_storage_object" "gpt_instruction" {
  bucket = yandex_storage_bucket.bot_gpt_instructions.bucket
  key    = "instruction.txt"
  source = var.instruction_file_path

}

resource "yandex_iam_service_account_api_key" "sa_api_key" {
  service_account_id = var.service_account_id
  description        = "API key for Yandex AI services"
}


data "archive_file" "bot_code_zip" {
  type        = "zip"
  source_file  = "${path.module}/target/cheatsheet_itis_2025_vvot02_bot-1.0-SNAPSHOT.jar"
  output_path = "${path.module}/bot_code.zip"
}

resource "yandex_function" "telegram_bot" {
  name        = "telegram-bot-function"
  folder_id   = var.folder_id
  runtime     = "java17"
  entrypoint  = "Pack.Bot"
  memory      = 512
  execution_timeout = 60

  environment = {
    TELEGRAM_BOT_TOKEN         = var.tg_bot_key
    BUCKET_NAME           = yandex_storage_bucket.bot_gpt_instructions.bucket
    OBJECT_KEY            = yandex_storage_object.gpt_instruction.key
    YANDEX_API_KEY        = yandex_iam_service_account_api_key.sa_api_key.secret_key
    FOLDER_ID = var.folder_id
  }

  content {
    zip_filename = data.archive_file.bot_code_zip.output_path
  }
  user_hash = data.archive_file.bot_code_zip.output_sha256
}

resource "yandex_function_iam_binding" "invoker-public" {
  function_id = yandex_function.telegram_bot.id
  role        = "functions.functionInvoker"
  members     = ["system:allUsers"]
}

resource "null_resource" "set_webhook" {
  depends_on = [yandex_function_iam_binding.invoker-public]

  provisioner "local-exec" {
    command = "Invoke-WebRequest -Uri \"https://api.telegram.org/bot${var.tg_bot_key}/setWebhook\" -Method POST -Headers @{\"Content-Type\"=\"application/json\"} -Body '{\"url\": \"https://functions.yandexcloud.net/${yandex_function.telegram_bot.id}\"}'"
    interpreter = ["PowerShell", "-Command"]
  }

  provisioner "local-exec" {
    when    = destroy
    command = "Invoke-WebRequest -Uri \"https://api.telegram.org/bot${self.triggers.bot_token}/deleteWebhook\" -Method POST"
    interpreter = ["PowerShell", "-Command"]
  }

  triggers = {
    bot_token   = var.tg_bot_key
    function_id = yandex_function.telegram_bot.id
  }
}

output "function_id" {
  value       = yandex_function.telegram_bot.id
  description = "Cloud Function ID"
}

output "function_url" {
  value       = "https://functions.yandexcloud.net/${yandex_function.telegram_bot.id}"
  description = "Cloud Function invoke URL"
}
