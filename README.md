# SV Menu Parser

[![](https://jitpack.io/v/WhySoBad/SVMenuParser.svg)](https://jitpack.io/#WhySoBad/SVMenuParser/)

A java library to parse menu data from pdfs that are provided by the SV Group. A pdf document, containing the week
overview of menus, is read and serialized for future use by machines.

## Usage

### Version

The current most up-to-date version is **1.3**.

### Getting the Library

You can get the latest version of this library over the service [JitPack](https://jitpack.io/#WhySoBad/SVMenuParser/),
by for example, adding the following to your build.gradle file.

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.WhySoBad:SVMenuParser:[Version]'
}
```

### In Code
Documentation coming soon™. Meanwhile, you could look at the javadoc.

## Principles
This library reads, as mentioned, pdfs. It does so by using pdf box, to read and parse them. With that a few challenges arise.
### PDF Types
While we were working on this library, we noticed that there were two distinct types of menu pdfs provided by the SV Group.

In the first and new type, the headers of the menus use a normal font, with all capital letters, varying size to accommodate for word capitalization. This type can be serialized relatively easily.

In the second, and older type, the font used in the headers consists of special unicodes, which do not relate to our common alphabet. Thus, this library uses a unicode mapping techinque, so that every unicode is mapped to its own corresponding latin letter. This map is located in the resources directory and is fitted to the font that we discovered. If any other font is being used, this map can easily be changed.
### Menu Labels
For SV menus, it is also common that they have special labels like vegetarian or vegan. To characterize those labels, a label found in the pdf is compared to saved referenced images. Since these labels may change or new labels may be added, those reference images can also easily be updated in the resource folder.

## License

Coming Soon™