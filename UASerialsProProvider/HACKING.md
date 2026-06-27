# Як знайти ключ дешифрування AES (password)

Сайт uaserials.com шифрує посилання на плеєр за допомогою CryptoJS AES-256-CBC.
Пароль для дешифрування захований в обфускованому JavaScript.

Якщо пароль змінюється (відео перестає відтворюватись), його можна знайти так:

## 1. Знайти файл з викликом CryptoJSAesDecrypt

Відкрийте будь-яку сторінку серіалу, напр. через DevTools браузера.
У вкладці Sources знайдіть JS-файли з `templates/uaserials2020/js/`.
Потрібні два файли:

- **Файл із функцією дешифрування** (напр. `ed90ae36.min.js`) — містить:
  ```
  window['CryptoJSAesDecrypt'] = function(password, encryptedJson) {
      // CryptoJS.PBKDF2(password, salt, {hasher: SHA512, keySize: 8, iterations: 999})
      // AES-256-CBC
  }
  ```
  Алгоритм фіксований і не змінюється: **PBKDF2-SHA512, 999 ітерацій, keySize 8 (256 bit)**.

- **Файл із викликом** (напр. `fe68ee32.min.js`) — містить:
  ```js
  var dd = _0xXXXX(0xNNN) + _0xXXXX(0xNNN) + '25';  // <-- це і є пароль
  // ...
  JSON.parse(CryptoJSAesDecrypt(dd, control.dataset.tag1))
  ```

## 2. Декодувати пароль

Код обфускований. Пароль будується з двох частин зі string table + суфікса.
String table кодована в кастомному base64 (lowercase-first alphabet).

### Швидкий спосіб (Node.js)

```js
// Кастомний base64 декодер (lowercase-first алфавіт)
const custom = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/';
const std    = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function decode(str) {
    let mapped = '';
    for (const ch of str) {
        const idx = custom.indexOf(ch);
        mapped += idx >= 0 ? std[idx] : ch;
    }
    return Buffer.from(mapped, 'base64').toString('utf8');
}

// 1. Скопіювати string table з _0x1848() у файлі
var table = ['zw1LBNq', 'CMvWBgfJzq', /* ... увесь масив ... */];

// 2. Знайти offset: _0x231190 = _0x231190 - OFFSET
//    OFFSET обчислюється з виразу біля function _0x484c / _0x4bfc33
var OFFSET = 0x135; // = 309 для fe68ee32.min.js

// 3. Спробувати всі ротації масиву (обфускатор циклічно зсуває елементи)
for (var rot = 0; rot < table.length; rot++) {
    var p1 = decode(table[0x13f - OFFSET]);  // індекс з коду: _0xXXXX(0x13f)
    var p2 = decode(table[0x185 - OFFSET]);  // індекс з коду: _0xXXXX(0x185)
    var pw = p1 + p2 + '25';                 // суфікс з коду
    if (/^[0-9A-F]+$/i.test(pw))             // шукаємо hex-подібний пароль
        console.log("rot=" + rot + ": " + pw);
    table.push(table.shift());               // наступна ротація
}
```

Пароль має виглядати як hex-рядок, напр. `297796CCB81D255125`.

### Повільний, але надійний спосіб

Відкрити файл у браузері → DevTools → Console.
Поставити breakpoint на рядок з `CryptoJSAesDecrypt(dd, ...)` і подивитись значення `dd`.

## 3. Перевірити пароль

```js
const crypto = require('crypto');
function decrypt(password, json) {
    const d = JSON.parse(json);
    const salt = Buffer.from(d.salt, 'hex');
    const iv = Buffer.from(d.iv, 'hex');
    const ct = Buffer.from(d.ciphertext, 'base64');
    const key = crypto.pbkdf2Sync(password, salt, 999, 32, 'sha512');
    const dec = crypto.createDecipheriv('aes-256-cbc', key, iv);
    return Buffer.concat([dec.update(ct), dec.final()]).toString();
}

// Взяти data-tag1 з будь-якої сторінки серіалу
const encrypted = '{"ciphertext":"...","iv":"...","salt":"..."}';
console.log(decrypt("297796CCB81D255125", encrypted));
// Має вивести JSON з посиланнями на плеєр
```

## 4. Оновити в коді

Замінити пароль у `UASerialsProProvider.kt`:

```kotlin
// рядок ~178
"297796CCB81D255125",  // <-- оновити тут
```

Підняти version у `build.gradle.kts` і зібрати: `./gradlew UASerialsProProvider:make`
