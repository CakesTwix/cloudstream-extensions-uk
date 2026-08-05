#!/usr/bin/env bash

# Перевіряє, що tvTypes у build.gradle.kts містить enum-типи CloudStream.
# Цей контракт захищає фільтр категорій у Settings → Extensions.
set -euo pipefail

declare -A expected=(
    [AnimeONProvider]='Anime AnimeMovie OVA'
    [AnimeUAProvider]='Anime AnimeMovie OVA'
    [AnitubeinuaProvider]='Anime AnimeMovie'
    [BambooUAProvider]='Anime AsianDrama'
    [BanderakinoProvider]='TvSeries'
    [CikavaIdeyaProvider]='Cartoon Movie TvSeries'
    [CoaninetProvider]='TvSeries'
    [DoramyWorldProvider]='AsianDrama Movie'
    [EneyidaProvider]='Anime Movie TvSeries'
    [HentaiUkrProvider]='NSFW'
    [KinoTronProvider]='Anime Cartoon Movie TvSeries'
    [KinoVezhaProvider]='Cartoon Movie TvSeries'
    [KlonTVProvider]='Anime Cartoon Movie TvSeries'
    [SerialnoProvider]='Cartoon TvSeries'
    [SimpsonsUATvProvider]='Cartoon TvSeries'
    [SyncPlugin]='Others'
    [UAFlixProvider]='Anime Cartoon Movie TvSeries'
    [UASerialsProProvider]='Anime Cartoon Movie TvSeries'
    [UFDubProvider]='Anime AnimeMovie AsianDrama Cartoon Movie TvSeries'
    [UakinoProvider]='Anime AsianDrama Movie TvSeries'
    [UnimayProvider]='Anime AnimeMovie'
)

failures=0

for module in "${!expected[@]}"; do
    file="$module/build.gradle.kts"
    if [[ ! -f "$file" ]]; then
        printf 'ПОМИЛКА: %s: відсутній build.gradle.kts\n' "$module"
        failures=$((failures + 1))
        continue
    fi

    block=$(awk '
        /tvTypes[[:space:]]*=[[:space:]]*listOf/ {
            print
            if ($0 ~ /\)/) exit
            reading = 1
            next
        }
        reading {
            print
            if ($0 ~ /\)/) exit
        }
    ' "$file")
    actual=$(printf '%s\n' "$block" | (grep -o '"[^"]*"' || true) | tr -d '"' | sort -u | tr '\n' ' ' | sed 's/[[:space:]]*$//')
    want=$(printf '%s\n' "${expected[$module]}" | tr ' ' '\n' | sort -u | tr '\n' ' ' | sed 's/[[:space:]]*$//')

    if [[ "$actual" != "$want" ]]; then
        printf 'ПОМИЛКА: %s: очікувалося [%s], отримано [%s]\n' "$module" "$want" "$actual"
        failures=$((failures + 1))
    fi
done

if (( failures > 0 )); then
    printf '\nЗнайдено невідповідностей категорій: %d\n' "$failures"
    exit 1
fi

printf 'Категорії всіх %d плагінів і провайдерів відповідають контракту.\n' "${#expected[@]}"
