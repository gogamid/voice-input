cmake_minimum_required(VERSION 3.22.1)
project(tvusb LANGUAGES C)

add_library(tvusb SHARED usb_audio.c)
set_target_properties(tvusb PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES)
target_compile_options(tvusb PRIVATE -Wall -Wextra -Werror)
