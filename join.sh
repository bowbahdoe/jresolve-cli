set -o pipefail

join(){
    # If no arguments, do nothing.
    # This avoids confusing errors in some shells.
    if [ $# -eq 0 ]; then
        return
    fi

    local joiner="$1"
    shift

    while [ $# -gt 1 ]; do
        printf "%s%s" "$1" "$joiner"
        shift
    done

    printf '%s\n' "$1"
}

join : $(find target -type f -name "*.jar")