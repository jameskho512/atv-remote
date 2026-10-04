# Third-party notices

ATV Remote itself is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)). The components below are used under their own licences, which are compatible with the GPL v3.

## pyatv

Parts of the protocol implementation (message layouts, constants, and test vectors) follow the open-source project [pyatv](https://github.com/postlund/pyatv). This applies to the Companion, AirPlay/MRP, keyed-archive, and OPACK code under `app/src/main/java/app/atvremote/protocol/` and to test data in `app/src/test/`.

> # The MIT License (MIT)
>
> Copyright (c) 2020 Pierre Ståhl
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Libraries

The app is built with these libraries, which are not part of this repository and are downloaded by Gradle. If you distribute a built APK, include their licence notices.

| Library | Licence |
| --- | --- |
| Jetpack Compose, AndroidX (activity, lifecycle) | Apache License 2.0 |
| Kotlin and kotlinx.coroutines | Apache License 2.0 |
| Bouncy Castle (`bcprov`) | Bouncy Castle Licence (MIT-style) |
| Material Icons (`material-icons-extended`) | Apache License 2.0 |

## Trademarks

Apple, Apple TV, Siri, and tvOS are trademarks of Apple Inc. Sony and BRAVIA are trademarks of Sony Group Corporation. Dolby Vision is a trademark of Dolby Laboratories. This project is not affiliated with or endorsed by any of them.
