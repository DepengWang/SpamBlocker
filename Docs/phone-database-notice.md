# Offline phone-prefix database

`app/src/main/res/raw/phone.dat` is the `phone.dat` data file from
[pangongzi/phone](https://github.com/pangongzi/phone), used under the MIT
license declared by that project. The bundled snapshot is version `2502` and
contains seven-digit China phone-prefix records. The application reads it
locally with an independent Kotlin parser; phone numbers are not sent to a
network service.

The data describes original number-prefix allocation. Number portability can
make the current carrier differ from the recorded carrier.
