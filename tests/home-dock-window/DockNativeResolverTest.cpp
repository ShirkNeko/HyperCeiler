/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "../../app/src/main/cpp/dock_native_resolver.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>

template<class T> T read(const std::vector<char> &bytes, size_t offset) {
    assert(offset <= bytes.size() && sizeof(T) <= bytes.size() - offset);
    T result;
    std::memcpy(&result, bytes.data() + offset, sizeof(result));
    return result;
}

int main(int argc, char **argv) {
    using namespace dock_motion;
    assert(!resolve({}));
    assert(!call_target({0, {}}, 0));
    for (int arg = 1; arg < argc; ++arg) {
        std::ifstream file(argv[arg], std::ios::binary);
        assert(file.is_open());
        const std::vector<char> bytes{std::istreambuf_iterator<char>(file), {}};
        assert(read<uint32_t>(bytes, 0) == 0x464c457f && bytes[4] == 2 && bytes[5] == 1);
        // ELF64 header offsets are the file format ABI, not launcher addresses.
        const auto phoff = read<uint64_t>(bytes, 32);
        const auto phsize = read<uint16_t>(bytes, 54);
        const auto phcount = read<uint16_t>(bytes, 56);
        std::vector<std::vector<uint32_t>> storage;
        std::vector<CodeRange> ranges;
        for (size_t p = 0; p < phcount; ++p) {
            const auto pos = phoff + p * phsize;
            if (read<uint32_t>(bytes, pos) != 1 || (read<uint32_t>(bytes, pos + 4) & 5) != 5) continue;
            const auto offset = read<uint64_t>(bytes, pos + 8);
            const auto address = read<uint64_t>(bytes, pos + 16);
            const auto size = read<uint64_t>(bytes, pos + 32);
            assert(size % 4 == 0 && offset <= bytes.size() && size <= bytes.size() - offset);
            storage.emplace_back(size / 4);
            std::memcpy(storage.back().data(), bytes.data() + offset, size);
            ranges.push_back({address, storage.back()});
        }
        const auto original = resolve(ranges);
        assert(original);
        std::cout << argv[arg] << " scale=" << std::hex << original->scale
            << " animate=" << original->animate << " set=" << original->set << std::dec
            << " CID=" << original->layout.params_class_id << '\n';
        for (auto &range : ranges) range.address += 0x7123450000ULL;
        const auto relocated = resolve(ranges);
        assert(relocated && relocated->scale == original->scale + 0x7123450000ULL);
        assert(relocated->animate == original->animate + 0x7123450000ULL);
        assert(relocated->set == original->set + 0x7123450000ULL);
        // Duplicate identities must fail closed, never select the first match.
        auto duplicate = ranges;
        duplicate.push_back(ranges.front());
        assert(!resolve(duplicate));
        const auto scale = at(ranges, relocated->scale, kScaleShape.words);
        auto *instruction = const_cast<uint32_t *>(scale.data());
        const auto saved = *instruction;
        *instruction = 0;
        assert(!resolve(ranges));
        *instruction = saved;
        const auto factory = find(ranges, kFactoryShape).front();
        const auto params = find(ranges, kParamsShape).front();
        const auto setter = find(ranges, kSetShape).front();
        auto shift_field = [](const Match &match, size_t index) {
            auto *word = const_cast<uint32_t *>(&match.words[index]);
            *word = (*word & ~0x001ff000u) | (static_cast<uint32_t>(field_offset(*word) + 8) << 12);
        };
        for (auto index : {11u, 35u, 131u, 137u}) shift_field(params, index);
        for (auto index : {1u, 4u, 12u, 14u}) shift_field(factory, index);
        for (auto index : {51u, 69u}) shift_field(setter, index);
        const auto allocation = at(ranges, *call_target(factory, 0), 3);
        auto *tag = const_cast<uint32_t *>(allocation.data());
        const uint32_t new_tag = (*allocation_tag(allocation, 2) & 0xfffu) | (2011u << kClassIdShift);
        tag[0] = (tag[0] & ~0x001fffe0u) | ((new_tag & 0xffff) << 5);
        tag[1] = (tag[1] & ~0x001fffe0u) | ((new_tag >> 16) << 5);
        const auto moved = resolve(ranges);
        assert(moved && moved->layout.params_class_id == 2011);
        assert(moved->layout.alpha_offset == original->layout.alpha_offset + 8);
        assert(moved->layout.scale_offset == original->layout.scale_offset + 8);
        assert(moved->layout.surface_offset == original->layout.surface_offset + 8);
        assert(moved->layout.recents_offset == original->layout.recents_offset + 8);
        // One inconsistent accessor must reject the entire resolution.
        shift_field(setter, 51);
        assert(!resolve(ranges));
    }
    std::cout << "Dynamic resolver tests passed\n";
}
