# Claude Code Project Context: LocalMediaHub (C/S System)

这是一个本地媒体资源管理系统。服务端运行在 PC 端，负责扫描和提供媒体流；客户端为原生 Android 应用，用于浏览和播放。

## 技术栈
- **Server (后端):** Python 3.10+ / FastAPI / Uvicorn
- **Client (前端):** Android Native / Kotlin / Jetpack Compose
- **通信协议:** HTTP (REST API / Streaming)
- **主要功能:** 本地文件系统扫描、视频流播放、图片预览

## 常用命令

### Backend (Server)
- **安装依赖:** `pip install fastapi uvicorn`
- **启动服务:** `python main.py` 或 `uvicorn main:app --reload --host 0.0.0.0`
- **测试接口:** `curl http://localhost:8000/docs`

### Frontend (Android)
- **编译项目:** `./gradlew assembleDebug`
- **运行单元测试:** `./gradlew test`
- **连接真机/模拟器安装:** `./gradlew installDebug`
- **Lint 检查:** `./gradlew lint`

## 项目结构规范
- `/server`: Python 后端源代码
    - `main.py`: 程序入口
    - `scanner.py`: 本地磁盘扫描逻辑
    - `api/`: 路由定义
- `/android`: Android Studio 项目根目录
    - `app/src/main/java/.../ui/`: Compose 组件
    - `app/src/main/java/.../network/`: Retrofit 接口定义
    - `app/src/main/java/.../data/`: 模型类与仓库层

## 编码规则

### Python (Server)
- 使用 **FastAPI** 异步 (`async def`) 处理 I/O 密集型任务（如文件扫描）。
- 路径处理必须兼容 Windows/Linux（使用 `pathlib`）。
- 视频播放需支持 **Range Requests** 以实现进度条拖动。
- 遵循 PEP 8 规范，使用类型注解 (Type Hints)。

### Kotlin (Android)
- **UI:** 必须使用 **Jetpack Compose** 进行界面开发。
- **架构:** 遵循 **MVVM** (Model-View-ViewModel) 模式。
- **网络:** 使用 **Retrofit + OkHttp** 进行 API 调用。
- **图片加载:** 推荐使用 **Coil** 加载网络/本地图片。
- **视频播放:** 使用 **ExoPlayer (Media3)**。
- 异步操作使用 **Coroutines** (协程)。

## 核心逻辑定义
1. **Server 权限:** Server 端需提供配置界面或配置文件，指定允许 App 访问的根文件夹路径。
2. **发现机制:** 初期可手动输入 PC IP 地址，后期考虑集成 mDNS/NSD (Network Service Discovery) 自动发现服务端。
3. **媒体处理:** 
- 视频：直接通过 HTTP Stream 传输。
- 图片：Server 端需提供缩略图生成接口（使用 Pillow），避免 App 端加载原图导致内存溢出。