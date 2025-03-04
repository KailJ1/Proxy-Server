# Proxy Server

Этот проект представляет собой простой прокси-сервер с базовой аутентификацией, реализованный на Java с использованием Netty и YAML для конфигурации.

## Возможности
- Запуск HTTP-прокси-сервера
- Поддержка базовой аутентификации
- Настройки сервера в файле `config.yml`

## Установка и запуск

### 1. Клонирование репозитория
```sh
git clone https://github.com/KailJ1/Proxy-Server.git
cd proxy-server/ProxyServer-main
```

### 2. Сборка проекта с помощью Gradle
```sh
./gradlew shadowJar
```

### 3. Запуск сервера
```sh
java -jar build/libs/ProxyServer-1.0-all.jar
```

## Конфигурация
При первом запуске автоматически создается файл `config.yml` со случайными данными для аутентификации. Структура файла:
```yaml
host: 0.0.0.0
port: 33526
auth_username: wzQbml
auth_password: ZH1GGo
```

При необходимости можно вручную изменить `config.yml` и перезапустить сервер.

## Зависимости
- Java 17+
- Netty
- SnakeYAML

## Лицензия
Этот проект распространяется под лицензией MIT. См. файл [LICENSE](LICENSE) для получения дополнительной информации.

